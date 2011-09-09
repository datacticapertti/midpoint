/*
 * Copyright (c) 2011 Evolveum
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://www.opensource.org/licenses/cddl1 or
 * CDDLv1.0.txt file in the source code distribution.
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 *
 * Portions Copyrighted 2011 [name of copyright owner]
 */
package com.evolveum.midpoint.model.controller.handler;

import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;

import org.apache.commons.lang.StringUtils;
import org.w3c.dom.Element;

import com.evolveum.midpoint.common.patch.PatchXml;
import com.evolveum.midpoint.common.result.OperationResult;
import com.evolveum.midpoint.model.controller.ModelController;
import com.evolveum.midpoint.model.controller.ModelControllerImpl;
import com.evolveum.midpoint.model.controller.ModelUtils;
import com.evolveum.midpoint.model.controller.SchemaHandler;
import com.evolveum.midpoint.provisioning.api.ProvisioningService;
import com.evolveum.midpoint.repo.api.RepositoryService;
import com.evolveum.midpoint.schema.XsdTypeConverter;
import com.evolveum.midpoint.schema.constants.ObjectTypes;
import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.schema.exception.ConsistencyViolationException;
import com.evolveum.midpoint.schema.exception.ObjectAlreadyExistsException;
import com.evolveum.midpoint.schema.exception.ObjectNotFoundException;
import com.evolveum.midpoint.schema.exception.SchemaException;
import com.evolveum.midpoint.schema.exception.SystemException;
import com.evolveum.midpoint.schema.holder.XPathHolder;
import com.evolveum.midpoint.schema.holder.XPathSegment;
import com.evolveum.midpoint.schema.util.JAXBUtil;
import com.evolveum.midpoint.schema.util.ObjectTypeUtil;
import com.evolveum.midpoint.util.DOMUtil;
import com.evolveum.midpoint.util.logging.LoggingUtils;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.util.patch.PatchException;
import com.evolveum.midpoint.xml.ns._public.common.common_1.AccountShadowType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.AssignmentType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.CredentialsType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.ObjectModificationType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.PropertyModificationType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.PropertyModificationTypeType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.PropertyReferenceListType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.ResourceType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.RoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.SchemaHandlingType.AccountType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.SystemConfigurationType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.UserTemplateType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.UserType;

/**
 * 
 * @author lazyman
 * 
 */
public class UserTypeHandler extends BasicHandler {

	private static final Trace LOGGER = TraceManager.getTrace(UserTypeHandler.class);

	/**
	 * Enum is used during processing AssignmentType objects in user
	 */
	private static enum AssignOperation {
		ADD, DELETE
	}

	public UserTypeHandler(ModelController modelController, ProvisioningService provisioning,
			RepositoryService repository, SchemaHandler schemaHandler) {
		super(modelController, provisioning, repository, schemaHandler);
	}

	@SuppressWarnings("unchecked")
	public <T extends ObjectType> void modifyObjectWithExclusion(Class<T> type,
			ObjectModificationType change, String accountOid, OperationResult result)
			throws ObjectNotFoundException, SchemaException {
		UserType user = (UserType) getObject(UserType.class, change.getOid(),
				new PropertyReferenceListType(), result);

		// processing add account
		processAddDeleteAccountFromChanges(change, user, result);

		getRepository().modifyObject(UserType.class, change, result);
		try {
			PatchXml patchXml = new PatchXml();
			String u = patchXml.applyDifferences(change, user);
			user = ((JAXBElement<UserType>) JAXBUtil.unmarshal(u)).getValue();
		} catch (Exception ex) {
			LoggingUtils.logException(LOGGER, "Couldn't patch user {}", ex, user.getName());
		}

		PropertyModificationType userActivationChanged = hasUserActivationChanged(change);
		if (userActivationChanged != null) {
			LOGGER.debug("User activation status changed, enabling/disabling accounts in next step.");
		}

		// from now on we have updated user, next step is processing
		// outbound for every account or enable/disable account if needed
		modifyAccountsAfterUserWithExclusion(user, change, userActivationChanged, accountOid, result);
	}

	public <T extends ObjectType> void deleteObject(Class<T> type, String oid, OperationResult result)
			throws ObjectNotFoundException, ConsistencyViolationException {
		try {
			UserType user = getObject(UserType.class, oid,
					ModelUtils.createPropertyReferenceListType("account", "resource"), result);
			processAssignments(user, user.getAssignment(), result, AssignOperation.DELETE);
			deleteUserAccounts((UserType) user, result);

			getRepository().deleteObject(UserType.class, oid, result);
			result.recordSuccess();
		} catch (ObjectNotFoundException ex) {
			LoggingUtils.logException(LOGGER, "Couldn't delete object with oid {}", ex, oid);
			result.recordFatalError("Couldn't find object with oid '" + oid + "'.", ex);
			throw ex;
		} catch (Exception ex) {
			LoggingUtils.logException(LOGGER,
					"Couldn't delete object with oid {}, potential consistency violation", ex, oid);
			result.recordFatalError("Couldn't delete object with oid '" + oid
					+ "', potential consistency violation");
			throw new ConsistencyViolationException("Couldn't delete object with oid '" + oid
					+ "', potential consistency violation", ex);
		} finally {
			LOGGER.debug(result.dump());
		}
	}

	public String addObject(ObjectType object, OperationResult result) throws ObjectAlreadyExistsException,
			ObjectNotFoundException {
		return addUser((UserType) object, null, result);
	}

	public String addUser(UserType user, UserTemplateType userTemplate, OperationResult result)
			throws ObjectAlreadyExistsException, ObjectNotFoundException {
		if (userTemplate == null) {
			SystemConfigurationType systemConfiguration = getSystemConfiguration(result);
			userTemplate = systemConfiguration.getDefaultUserTemplate();
		}
		result.addParams(new String[] { "user", "userTemplate" }, user, userTemplate);

		if (userTemplate != null) {
			LOGGER.debug("Adding user {}, oid {} using template {}, oid {}.", new Object[] { user.getName(),
					user.getOid(), userTemplate.getName(), userTemplate.getOid() });
		} else {
			LOGGER.debug("Adding user {}, oid {} using no template.",
					new Object[] { user.getName(), user.getOid() });
		}

		String oid = null;
		try {
			// TODO: process add account should be removed, we have to use only
			// assignments and there should be account if needed
			processAddAccountFromUser(user, result);
			processAssignments(user, user.getAssignment(), result, AssignOperation.ADD);

			user = processUserTemplateForUser(user, userTemplate, result);
			oid = getRepository().addObject(user, result);
			result.recordSuccess();
		} catch (ObjectAlreadyExistsException ex) {
			result.recordFatalError("Couldn't add user '" + user.getName() + "', oid '" + user.getOid()
					+ "' because user already exists.", ex);
			throw ex;
		} catch (Exception ex) {
			if (userTemplate != null) {
				LoggingUtils.logException(LOGGER, "Couldn't add user {}, oid {} using template {}, oid {}",
						ex, user.getName(), user.getOid(), userTemplate.getName(), userTemplate.getOid());
				result.recordFatalError("Couldn't add user " + user.getName() + ", oid '" + user.getOid()
						+ "' using template " + userTemplate.getName() + ", oid '" + userTemplate.getOid()
						+ "'", ex);
			} else {
				LoggingUtils.logException(LOGGER, "Couldn't add user {}, oid {} without user template", ex,
						user.getName(), user.getOid());
				result.recordFatalError("Couldn't add user " + user.getName() + ", oid '" + user.getOid()
						+ "' without user template.", ex);
			}
			throw new SystemException(ex.getMessage(), ex);
		} finally {
			LOGGER.debug(result.dump());
		}

		return oid;
	}

	private void processAddAccountFromUser(UserType user, OperationResult result) {
		List<AccountShadowType> accountsToDelete = new ArrayList<AccountShadowType>();
		for (AccountShadowType account : user.getAccount()) {
			try {
				if (account.getActivation() == null) {
					account.setActivation(user.getActivation());
				}
				// MID-72
				pushPasswordFromUserToAccount(user, account, result);

				String newAccountOid = getModelController().addObject(account, result);
				ObjectReferenceType accountRef = ModelUtils.createReference(newAccountOid,
						ObjectTypes.ACCOUNT);
				user.getAccountRef().add(accountRef);
				accountsToDelete.add(account);
			} catch (SystemException ex) {
				throw ex;
			} catch (Exception ex) {
				throw new SystemException("Couldn't process add account.", ex);
			}
		}
		user.getAccount().removeAll(accountsToDelete);
	}

	/**
	 * MID-72, MID-73 password push from user to account
	 */
	private void pushPasswordFromUserToAccount(UserType user, AccountShadowType account,
			OperationResult result) throws ObjectNotFoundException {
		ResourceType resource = account.getResource();
		if (resource == null) {
			resource = getObject(ResourceType.class, account.getResourceRef().getOid(),
					new PropertyReferenceListType(), result);
		}
		AccountType accountHandling = ModelUtils.getAccountTypeFromHandling(account, resource);
		boolean pushPasswordToAccount = false;
		if (accountHandling.getCredentials() != null
				&& accountHandling.getCredentials().isOutboundPassword() != null) {
			pushPasswordToAccount = accountHandling.getCredentials().isOutboundPassword();
		}
		if (pushPasswordToAccount && user.getCredentials() != null
				&& user.getCredentials().getPassword() != null) {
			CredentialsType credentials = account.getCredentials();
			if (credentials == null) {
				credentials = new CredentialsType();
				account.setCredentials(credentials);
			}
			credentials.setPassword(user.getCredentials().getPassword());
		}
	}

	private void deleteUserAccounts(UserType user, OperationResult result) throws ObjectNotFoundException {
		List<AccountShadowType> accountsToBeDeleted = new ArrayList<AccountShadowType>();
		for (AccountShadowType account : user.getAccount()) {
			try {
				getModelController().deleteObject(AccountShadowType.class, account.getOid(), result);
				accountsToBeDeleted.add(account);
			} catch (ConsistencyViolationException ex) {
				// TODO: handle this
				LoggingUtils.logException(LOGGER, "TODO handle ConsistencyViolationException", ex);
			}
		}

		user.getAccount().removeAll(accountsToBeDeleted);

		List<ObjectReferenceType> refsToBeDeleted = new ArrayList<ObjectReferenceType>();
		for (ObjectReferenceType accountRef : user.getAccountRef()) {
			try {
				getModelController().deleteObject(AccountShadowType.class, accountRef.getOid(), result);
				refsToBeDeleted.add(accountRef);
			} catch (ConsistencyViolationException ex) {
				// TODO handle this
				LoggingUtils.logException(LOGGER, "TODO handle ConsistencyViolationException", ex);
			}
		}
		user.getAccountRef().removeAll(refsToBeDeleted);

		// If list is empty then skip processing user have no accounts.
		if (accountsToBeDeleted.isEmpty() && refsToBeDeleted.isEmpty()) {
			return;
		}

		ObjectModificationType change = createUserModification(accountsToBeDeleted, refsToBeDeleted);
		change.setOid(user.getOid());
		try {
			getRepository().modifyObject(UserType.class, change, result);
		} catch (ObjectNotFoundException ex) {
			throw ex;
		} catch (Exception ex) {
			LoggingUtils.logException(LOGGER, "Couldn't update user {} after accounts was deleted", ex,
					user.getName());
			throw new SystemException(ex.getMessage(), ex);
		}
	}

	private ObjectModificationType createUserModification(List<AccountShadowType> accountsToBeDeleted,
			List<ObjectReferenceType> refsToBeDeleted) {
		ObjectModificationType change = new ObjectModificationType();
		for (ObjectReferenceType reference : refsToBeDeleted) {
			PropertyModificationType propertyChangeType = ObjectTypeUtil.createPropertyModificationType(
					PropertyModificationTypeType.delete, null, SchemaConstants.I_ACCOUNT_REF, reference);

			change.getPropertyModification().add(propertyChangeType);
		}

		for (AccountShadowType account : accountsToBeDeleted) {
			PropertyModificationType propertyChangeType = ObjectTypeUtil.createPropertyModificationType(
					PropertyModificationTypeType.delete, null, SchemaConstants.I_ACCOUNT, account.getOid());

			change.getPropertyModification().add(propertyChangeType);
		}

		return change;
	}

	private void modifyAccountsAfterUserWithExclusion(UserType user, ObjectModificationType change,
			PropertyModificationType userActivationChanged, String accountOid, OperationResult result) {
		List<ObjectReferenceType> accountRefs = user.getAccountRef();
		for (ObjectReferenceType accountRef : accountRefs) {
			OperationResult subResult = result.createSubresult(ModelControllerImpl.UPDATE_ACCOUNT);
			subResult.addParams(new String[] { "change", "accountOid", "object", "accountRef" }, change,
					accountOid, user, accountRef);
			if (StringUtils.isNotEmpty(accountOid) && accountOid.equals(accountRef.getOid())) {
				subResult.computeStatus("Account excluded during modification, skipped.");
				// preventing cycles while updating resource object shadows
				continue;
			}

			try {
				AccountShadowType account = getObject(AccountShadowType.class, accountRef.getOid(),
						ModelUtils.createPropertyReferenceListType("Resource"), subResult);

				ObjectModificationType accountChange = null;
				try {
					accountChange = getSchemaHandler().processOutboundHandling(user, account, subResult);
				} catch (SchemaException ex) {
					LoggingUtils.logException(LOGGER, "Couldn't update outbound handling for account {}", ex,
							accountRef.getOid());
					subResult.recordFatalError(ex);
				}

				if (accountChange == null) {
					accountChange = new ObjectModificationType();
					accountChange.setOid(account.getOid());
				}

				if (userActivationChanged != null) {
					PropertyModificationType modification = new PropertyModificationType();
					modification.setModificationType(PropertyModificationTypeType.replace);
					modification.setPath(userActivationChanged.getPath());
					modification.setValue(userActivationChanged.getValue());
					accountChange.getPropertyModification().add(modification);
				}

				getModelController().modifyObjectWithExclusion(AccountShadowType.class, accountChange,
						accountOid, subResult);
			} catch (Exception ex) {
				LoggingUtils.logException(LOGGER, "Couldn't update account {}", ex, accountRef.getOid());
				subResult.recordFatalError(ex);
			} finally {
				subResult.computeStatus("Couldn't update account '" + accountRef.getOid() + "'.");
			}
		}
	}

	private PropertyModificationType hasUserActivationChanged(ObjectModificationType change) {
		for (PropertyModificationType modification : change.getPropertyModification()) {
			XPathHolder xpath = new XPathHolder(modification.getPath());
			List<XPathSegment> segments = xpath.toSegments();
			if (segments == null || segments.isEmpty() || segments.size() > 1) {
				continue;
			}

			if (SchemaConstants.ACTIVATION.equals(segments.get(0).getQName())) {
				return modification;
			}
		}

		return null;
	}

	private void processAddDeleteAccountFromChanges(ObjectModificationType change, UserType userBeforeChange,
			OperationResult result) {
		// MID-72, MID-73 password push from user to account
		// TODO: look for password property modification and next use it while
		// creating account. If account schemahandling credentials
		// outboundPassword is true, we have to push password
		for (PropertyModificationType propertyChange : change.getPropertyModification()) {
			if (propertyChange.getValue() == null || propertyChange.getValue().getAny().isEmpty()) {
				continue;
			}

			Object node = propertyChange.getValue().getAny().get(0);
			if (!SchemaConstants.I_ACCOUNT.equals(JAXBUtil.getElementQName(node))) {
				continue;
			}

			try {
				switch (propertyChange.getModificationType()) {
					case add:
						processAddAccountFromChanges(propertyChange, node, userBeforeChange, result);
						break;
					case delete:
						processDeleteAccountFromChanges(propertyChange, node, userBeforeChange, result);
						break;
				}
			} catch (SystemException ex) {
				throw ex;
			} catch (Exception ex) {
				final String message = propertyChange.getModificationType() == PropertyModificationTypeType.add ? "Couldn't process add account."
						: "Couldn't process delete account.";
				throw new SystemException(message, ex);
			}
		}
	}

	private void processDeleteAccountFromChanges(PropertyModificationType propertyChange, Object node,
			UserType userBeforeChange, OperationResult result) throws JAXBException {
		AccountShadowType account = XsdTypeConverter.toJavaValue(node, AccountShadowType.class);

		ObjectReferenceType accountRef = ModelUtils.createReference(account.getOid(), ObjectTypes.ACCOUNT);
		PropertyModificationType deleteAccountRefChange = ObjectTypeUtil.createPropertyModificationType(
				PropertyModificationTypeType.delete, null, SchemaConstants.I_ACCOUNT_REF, accountRef);

		propertyChange.setPath(deleteAccountRefChange.getPath());
		propertyChange.setValue(deleteAccountRefChange.getValue());
	}

	@SuppressWarnings("unchecked")
	private void processAddAccountFromChanges(PropertyModificationType propertyChange, Object node,
			UserType userBeforeChange, OperationResult result) throws JAXBException, ObjectNotFoundException,
			PatchException, ObjectAlreadyExistsException, SchemaException {

		AccountShadowType account = XsdTypeConverter.toJavaValue(node, AccountShadowType.class);
		pushPasswordFromUserToAccount(userBeforeChange, account, result);

		ObjectModificationType accountChange = processOutboundSchemaHandling(userBeforeChange, account,
				result);
		if (accountChange != null) {
			PatchXml patchXml = new PatchXml();
			String accountXml = patchXml.applyDifferences(accountChange, account);
			account = ((JAXBElement<AccountShadowType>) JAXBUtil.unmarshal(accountXml)).getValue();
		}

		String newAccountOid = getModelController().addObject(account, result);
		ObjectReferenceType accountRef = ModelUtils.createReference(newAccountOid, ObjectTypes.ACCOUNT);
		Element accountRefElement = JAXBUtil.jaxbToDom(accountRef, SchemaConstants.I_ACCOUNT_REF,
				DOMUtil.getDocument());

		propertyChange.getValue().getAny().clear();
		propertyChange.getValue().getAny().add(accountRefElement);
	}

	private void processAssignments(UserType user, List<AssignmentType> assignments, OperationResult result,
			AssignOperation operation) {
		LOGGER.debug("Processing assignments ({}) for user {}, operation: {}.",
				new Object[] { assignments.size(), user.getName(), operation });

		for (AssignmentType assignment : assignments) {
			OperationResult subResult = result.createSubresult("Process assignment");
			if (AssignOperation.ADD.equals(operation)
					&& !ModelUtils.isActivationEnabled(assignment.getActivation())) {
				continue;
			}

			try {
				if (AssignOperation.ADD.equals(operation) && assignment.getAccountConstruction() != null) {
					processAccountConstruction(user, assignment.getAccountConstruction(), subResult);
				} else if (assignment.getTarget() != null) {
					assignTarget(user, assignment.getTarget(), subResult, operation);
				} else if (assignment.getTargetRef() != null) {
					assignTargetRef(user, assignment.getTargetRef(), subResult, operation);
				}
			} catch (Exception ex) {
				LoggingUtils.logException(LOGGER, "Couldn't process assignment number {} on user {}", ex,
						assignments.indexOf(assignment), user.getName());
				subResult.recordFatalError(ex.getMessage());
			} finally {
				subResult.computeStatus();
			}
		}
	}

	private void assignTargetRef(UserType user, ObjectReferenceType targetRef, OperationResult result,
			AssignOperation operation) throws ObjectNotFoundException {
		Class<? extends ObjectType> clazz = ObjectType.class;
		if (targetRef.getType() != null) {
			clazz = ObjectTypes.getObjectTypeFromTypeQName(targetRef.getType()).getClassDefinition();
		}
		ObjectType object = getObject(clazz, targetRef.getOid(), new PropertyReferenceListType(), result);
		assignTarget(user, object, result, operation);
	}

	private void assignTarget(UserType user, ObjectType target, OperationResult result,
			AssignOperation operation) {
		if (target instanceof RoleType) {
			assignRole(user, (RoleType) target, result, operation);
		} else if (target instanceof AccountShadowType) {
			assignAccount(user, (AccountShadowType) target, result, operation);
		}
	}

	private void assignRole(UserType user, RoleType role, OperationResult result, AssignOperation operation) {
		LOGGER.debug("Processing role {} assignment for user {}.",
				new Object[] { role.getName(), user.getName() });
		OperationResult subResult = result.createSubresult("Assign role");
		try {
			processAssignments(user, role.getAssignment(), subResult, operation);
		} catch (Exception ex) {
			LoggingUtils.logException(LOGGER, "Couldn't process role '{}' assignments ({})", ex,
					role.getName(), role.getAssignment().size());
		} finally {
			subResult.computeStatus("Couldn't process assignment for role '" + role.getName() + "'.",
					"Some minor problem occured while processing assignment for role '" + role.getName()
							+ "'.");
		}
	}

	/**
	 * Method takes {@link AccountShadowType} from assignment. In case of
	 * {@link AssignOperation} ADD, checks if is not already assigned to user
	 * and if not it adds account to provisioning and as accountRef to user. In
	 * case of {@link AssignOperation} DELETE method deletes account from
	 * system.
	 */
	private void assignAccount(UserType user, AccountShadowType account, OperationResult result,
			AssignOperation operation) {
		LOGGER.debug("Processing account {} assignment for user {}.",
				new Object[] { account.getName(), user.getName() });
		if (isAccountAssigned(user, account, result)) {
			switch (operation) {
				case DELETE:
					try {
						getModelController().deleteObject(AccountShadowType.class, account.getOid(), result);
					} catch (Exception ex) {
						LoggingUtils.logException(LOGGER, "Couldn't delete account {} from user {}", ex,
								account.getName(), user.getName());
					}
				case ADD:
					return;
			}
		}

		try {
			if (account.getActivation() == null) {
				account.setActivation(user.getActivation());
			}
			// MID-72
			pushPasswordFromUserToAccount(user, account, result);

			String newAccountOid = getModelController().addObject(account, result);
			ObjectReferenceType accountRef = ModelUtils.createReference(newAccountOid, ObjectTypes.ACCOUNT);
			user.getAccountRef().add(accountRef);
		} catch (SystemException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new SystemException("Couldn't process add account.", ex);
		}
	}

	/**
	 * This method check if parameter account is not already assigned to user
	 * selected in parameter user
	 * 
	 * @return true if account was created and assigned before, otherwise false
	 */
	private boolean isAccountAssigned(UserType user, AccountShadowType account, OperationResult result) {
		for (AccountShadowType existingAccount : user.getAccount()) {
			if (areAccountsEqual(account, existingAccount)) {
				return true;
			}
		}

		for (ObjectReferenceType accountRef : user.getAccountRef()) {
			if (!ObjectTypes.ACCOUNT.getQName().equals(accountRef.getType())) {
				continue;
			}

			try {
				AccountShadowType refferedAccount = getObject(AccountShadowType.class, accountRef.getOid(),
						ModelUtils.createPropertyReferenceListType("resource"), result);
				if (areAccountsEqual(account, refferedAccount)) {
					return true;
				}
			} catch (Exception ex) {
				LoggingUtils.logException(LOGGER, "Couldn't get account with oid '{}'", ex,
						accountRef.getOid());
			}
		}

		return false;
	}

	/**
	 * Method compare two accounts based on account name and referenced resource
	 * (oid)
	 * 
	 * @return true if two accounts are equal, otherwise false
	 */
	private boolean areAccountsEqual(AccountShadowType first, AccountShadowType second) {
		if (!areResourcesEqual(first, second)) {
			return false;
		}

		if (!first.getName().equals(second.getName())) {
			return false;
		}

		return true;
	}

	/**
	 * Method provides test for comparing resource oid values for two accounts
	 * 
	 * @return true if resource definition oid's in both accounts are equal
	 */
	private boolean areResourcesEqual(AccountShadowType first, AccountShadowType second) {
		String firstOid = null;
		if (first.getResourceRef() != null) {
			firstOid = first.getResourceRef().getOid();
		} else if (first.getResource() != null) {
			firstOid = first.getResource().getOid();
		}

		String secondOid = null;
		if (second.getResourceRef() != null) {
			secondOid = second.getResourceRef().getOid();
		} else if (first.getResource() != null) {
			secondOid = second.getResource().getOid();
		}

		return firstOid != null ? firstOid.equals(secondOid) : secondOid == null;
	}
}
