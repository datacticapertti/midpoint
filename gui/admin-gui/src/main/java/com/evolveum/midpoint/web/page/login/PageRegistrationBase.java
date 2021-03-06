package com.evolveum.midpoint.web.page.login;

import org.apache.wicket.RestartResponseException;
import org.apache.wicket.spring.injection.annot.SpringBean;

import com.evolveum.midpoint.gui.api.page.PageBase;
import com.evolveum.midpoint.model.api.AuthenticationEvaluator;
import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.util.Producer;
import com.evolveum.midpoint.util.exception.ObjectNotFoundException;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.xml.ns._public.common.common_3.SecurityPolicyType;

public class PageRegistrationBase extends PageBase {

	private static final long serialVersionUID = 1L;
	private static final String DOT_CLASS = PageRegistrationBase.class.getName() + ".";
	private static final String OPERATION_GET_SECURITY_POLICY = DOT_CLASS + "getSecurityPolicy";

	private static final Trace LOGGER = TraceManager.getTrace(PageSelfRegistration.class);

	@SpringBean(name = "authenticationEvaluator")
	private AuthenticationEvaluator authenticationEvaluator;
	
	private SelfRegistrationDto selfRegistrationDto;

	public PageRegistrationBase() {
		initSelfRegistrationConfiguration();
	}

	private void initSelfRegistrationConfiguration() {
		SecurityPolicyType securityPolicy = runPrivileged(new Producer<SecurityPolicyType>() {

			@Override
			public SecurityPolicyType run() {

				Task task = createAnonymousTask(OPERATION_GET_SECURITY_POLICY);
				task.setChannel(SchemaConstants.CHANNEL_GUI_SELF_REGISTRATION_URI);
				OperationResult result = new OperationResult(OPERATION_GET_SECURITY_POLICY);

				try {
					return getModelInteractionService().getSecurityPolicy(null, task, result);
				} catch (ObjectNotFoundException | SchemaException e) {
					LOGGER.error("Could not retrieve security policy");

				}
				return null;
			}

		});

		if (securityPolicy == null) {
			LOGGER.error("No security policy defined.");
			getSession()
					.error(createStringResource("PageSelfRegistration.securityPolicy.notFound").getString());
			throw new RestartResponseException(PageLogin.class);
		}

		this.selfRegistrationDto = new SelfRegistrationDto();
		try {
			this.selfRegistrationDto.initSelfRegistrationDto(securityPolicy);
		} catch (SchemaException e) {
			LOGGER.error("Failed to initialize self registration configuration.", e);
			getSession().error(
					createStringResource("PageSelfRegistration.selfRegistration.configuration.init.failed")
							.getString());
			throw new RestartResponseException(PageLogin.class);
		}

	}

	public SelfRegistrationDto getSelfRegistrationConfiguration() {

		if (selfRegistrationDto == null) {
			initSelfRegistrationConfiguration();
		}

		return selfRegistrationDto;

	}
	
	public AuthenticationEvaluator getAuthenticationEvaluator() {
		return authenticationEvaluator;
	}

}
