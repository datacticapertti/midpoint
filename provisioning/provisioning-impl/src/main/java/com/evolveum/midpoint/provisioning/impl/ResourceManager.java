package com.evolveum.midpoint.provisioning.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.xml.namespace.QName;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.Validate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.evolveum.midpoint.common.InternalMonitor;
import com.evolveum.midpoint.common.refinery.RefinedResourceSchema;
import com.evolveum.midpoint.prism.ComplexTypeDefinition;
import com.evolveum.midpoint.prism.Containerable;
import com.evolveum.midpoint.prism.Definition;
import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.PrismContainer;
import com.evolveum.midpoint.prism.PrismContainerDefinition;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismProperty;
import com.evolveum.midpoint.prism.delta.ContainerDelta;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.delta.PropertyDelta;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.prism.schema.PrismSchema;
import com.evolveum.midpoint.provisioning.api.GenericConnectorException;
import com.evolveum.midpoint.provisioning.ucf.api.ConnectorInstance;
import com.evolveum.midpoint.provisioning.ucf.api.GenericFrameworkException;
import com.evolveum.midpoint.repo.api.RepositoryService;
import com.evolveum.midpoint.schema.CapabilityUtil;
import com.evolveum.midpoint.schema.constants.ConnectorTestOperation;
import com.evolveum.midpoint.schema.processor.ObjectClassComplexTypeDefinition;
import com.evolveum.midpoint.schema.processor.ResourceAttributeContainerDefinition;
import com.evolveum.midpoint.schema.processor.ResourceAttributeDefinition;
import com.evolveum.midpoint.schema.processor.ResourceSchema;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.schema.util.ConnectorTypeUtil;
import com.evolveum.midpoint.schema.util.MiscSchemaUtil;
import com.evolveum.midpoint.schema.util.ObjectTypeUtil;
import com.evolveum.midpoint.schema.util.ResourceTypeUtil;
import com.evolveum.midpoint.util.DOMUtil;
import com.evolveum.midpoint.util.exception.CommunicationException;
import com.evolveum.midpoint.util.exception.ConfigurationException;
import com.evolveum.midpoint.util.exception.ObjectAlreadyExistsException;
import com.evolveum.midpoint.util.exception.ObjectNotFoundException;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.util.exception.SystemException;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.xml.ns._public.common.common_2a.AvailabilityStatusType;
import com.evolveum.midpoint.xml.ns._public.common.common_2a.CachingMetadataType;
import com.evolveum.midpoint.xml.ns._public.common.common_2a.CapabilitiesType;
import com.evolveum.midpoint.xml.ns._public.common.common_2a.CapabilityCollectionType;
import com.evolveum.midpoint.xml.ns._public.common.common_2a.ConnectorConfigurationType;
import com.evolveum.midpoint.xml.ns._public.common.common_2a.ConnectorType;
import com.evolveum.midpoint.xml.ns._public.common.common_2a.OperationalStateType;
import com.evolveum.midpoint.xml.ns._public.common.common_2a.ResourceType;
import com.evolveum.midpoint.xml.ns._public.common.common_2a.XmlSchemaType;
import com.evolveum.midpoint.xml.ns._public.resource.capabilities_2.ActivationCapabilityType;

@Component
public class ResourceManager {

	@Autowired(required = true)
	@Qualifier("cacheRepositoryService")
	private RepositoryService repositoryService;
	@Autowired(required = true)
	private ResourceCache resourceCache;
	@Autowired(required = true)
	private ConnectorManager connectorTypeManager;
	@Autowired(required = true)
	private PrismContext prismContext;

	private static final Trace LOGGER = TraceManager.getTrace(ResourceManager.class);
	
	private static final String OPERATION_COMPLETE_RESOURCE = ResourceManager.class.getName() + ".completeResource";
	
	public PrismObject<ResourceType> getResource(PrismObject<ResourceType> repositoryObject, OperationResult parentResult) throws ObjectNotFoundException, SchemaException, CommunicationException, ConfigurationException{
		PrismObject<ResourceType> cachedResource = resourceCache.get(repositoryObject);
		if (cachedResource != null) {
			return cachedResource;
		}
		PrismObject<ResourceType> completedResource = completeResource(repositoryObject, null, parentResult);
		

		if (LOGGER.isTraceEnabled()) {
			LOGGER.trace("Putting resource in cache:\n{}", completedResource.dump());
			Element xsdSchemaElement = ResourceTypeUtil.getResourceXsdSchema(completedResource);
			if (xsdSchemaElement == null) {
				LOGGER.trace("Schema: null");
			} else {
				LOGGER.trace("Schema:\n{}",
						DOMUtil.serializeDOMToString(ResourceTypeUtil.getResourceXsdSchema(completedResource)));
			}
		}
		OperationResult completeResourceResult = parentResult.findSubresult(OPERATION_COMPLETE_RESOURCE);
		if (completeResourceResult.isSuccess()) {
			// Cache only resources that are completely OK
			resourceCache.put(completedResource);
		}
		
		return completedResource;
	}
	
	public PrismObject<ResourceType> getResource(String oid, OperationResult parentResult) throws ObjectNotFoundException, SchemaException, CommunicationException, ConfigurationException{
//		ResourceType cachedResource = resourceCache.get(oid, version);
		PrismObject<ResourceType> repositoryObject = repositoryService.getObject(ResourceType.class, oid, parentResult);
		return getResource(repositoryObject, parentResult);
	}
	
	/**
	 * Make sure that the resource is complete.
	 * 
	 * It will check if the resource has a sufficiently fresh schema, etc.
	 * 
	 * Returned resource may be the same or may be a different instance, but it
	 * is guaranteed that it will be "fresher" and will correspond to the
	 * repository state (assuming that the provided resource also corresponded
	 * to the repository state).
	 * 
	 * The connector schema that was fetched before can be supplied to this
	 * method. This is just an optimization. It comes handy e.g. in test
	 * connection case.
	 * 
	 * Note: This is not really the best place for this method. Need to figure
	 * out correct place later.
	 * 
	 * @param repoResource
	 *            Resource to check
	 * @param resourceSchema
	 *            schema that was freshly pre-fetched (or null)
	 * @param parentResult
	 * 
	 * @return completed resource
	 * @throws ObjectNotFoundException
	 *             connector instance was not found
	 * @throws SchemaException
	 * @throws CommunicationException
	 *             cannot fetch resource schema
	 * @throws ConfigurationException
	 */
	private PrismObject<ResourceType> completeResource(PrismObject<ResourceType> repoResource, ResourceSchema resourceSchema,
			OperationResult parentResult) throws ObjectNotFoundException, SchemaException,
			CommunicationException, ConfigurationException {

		ResourceType repoResourceType = repoResource.asObjectable();
		// do not add as a subresult..it will be added later, if the completing
		// of resource will be successfull.if not, it will be only set as a
		// fetch result in the resource..
		OperationResult result = parentResult.createMinorSubresult(OPERATION_COMPLETE_RESOURCE);
		
		applyConnectorSchemaToResource(repoResource, result);
		
		if (isComplete(repoResource)) {
			// The resource is complete. Just make sure it has parsed resource and refined schema. We are going to cache
			// it, so we want to cache it with the parsed schemas
			RefinedResourceSchema.getRefinedSchema(repoResource);
			result.recordSuccessIfUnknown();
			return repoResource;
		}

		ConnectorInstance connector = null;
		try {
			connector = getConnectorInstance(repoResource, false, result);
		} catch (ObjectNotFoundException e) {
			String message = "Error resolving connector reference in " + repoResource
								+ ": Error creating connector instace: " + e.getMessage();
			// Catch the exceptions. There are not critical. We need to catch them all because the connector may
			// throw even undocumented runtime exceptions.
			// Even non-complete resource may still be usable. The fetchResult indicates that there was an error
			result.recordPartialError(message, e);
			return repoResource;
		} catch (CommunicationException e) {
			// The resource won't work, the connector was not initialized (most likely it cannot reach the schema)
			// But we still want to return the resource object that is (partailly) working. At least for reconfiguration.
			String message = "Error communicating with the " + repoResource
					+ ": " + e.getMessage();
			result.recordPartialError(message, e);
			return repoResource;
		} catch (ConfigurationException e) {
			String message = "Connector configuration error for the " + repoResource
					+ ": " + e.getMessage();
			result.recordPartialError(message, e);
			return repoResource;
		} catch (SchemaException e) {
			String message = "Schema error for the " + repoResource
					+ ": " + e.getMessage();
			result.recordPartialError(message, e);
			return repoResource;
		} catch (RuntimeException e) {
			String message = "Generic connector error for the " + repoResource
					+ ": " + e.getMessage();
			result.recordPartialError(message, e);
			return repoResource;
		}

		try {
			
			completeSchemaAndCapabilities(repoResource, resourceSchema, connector, result);
			
		} catch (Exception ex) {
			// Catch the exceptions. There are not critical. We need to catch them all because the connector may
			// throw even undocumented runtime exceptions.
			// Even non-complete resource may still be usable. The fetchResult indicates that there was an error
			result.recordPartialError("Cannot complete resource schema and capabilities: "+ex.getMessage(), ex);
			return repoResource;
		}
		
		// Now we need to re-read the resource from the repository and re-aply the schemas. This ensures that we will
		// cache the correct version and that we avoid race conditions, etc.
		
		PrismObject<ResourceType> newResource = repositoryService.getObject(ResourceType.class, repoResource.getOid(), result);
		applyConnectorSchemaToResource(newResource, result);
		
		// make sure it has parsed resource and refined schema. We are going to cache
		// it, so we want to cache it with the parsed schemas
		RefinedResourceSchema.getRefinedSchema(newResource);
		
		result.recordSuccessIfUnknown();

		return newResource;
	}

	private boolean isComplete(PrismObject<ResourceType> resource) {
		ResourceType resourceType = resource.asObjectable();
		Element xsdSchema = ResourceTypeUtil.getResourceXsdSchema(resource);
		if (xsdSchema == null) {
			return false;
		}
		CapabilitiesType capabilitiesType = resourceType.getCapabilities();
		if (capabilitiesType == null) {
			return false;
		}
		CachingMetadataType capCachingMetadata = capabilitiesType.getCachingMetadata();
		if (capCachingMetadata == null) {
			return false;
		}
		return true;
	}


	private void completeSchemaAndCapabilities(PrismObject<ResourceType> resource, ResourceSchema resourceSchema,
			ConnectorInstance connector, OperationResult result) throws SchemaException, CommunicationException, ObjectNotFoundException, GenericFrameworkException, ConfigurationException {
		ResourceType resourceType = resource.asObjectable();

		if (resourceSchema == null) { // unless it has been already pulled
			LOGGER.trace("Fetching resource schema for {}", resource);
			
			// Fetch schema from connector, UCF will convert it to
			// Schema Processor format and add all necessary annotations
			InternalMonitor.recordConnectorSchemaFetch();
			resourceSchema = connector.fetchResourceSchema(result);

			if (resourceSchema == null) {
				LOGGER.warn("No resource schema fetched from {}", resource);
			} else {
				LOGGER.debug("Fetched resource schema for {}: {} definitions", resource, resourceSchema.getDefinitions().size());
			}

		}
		
		if (resourceSchema == null) {
			return;
		}
		
		Collection<ItemDelta<?>> modifications = new ArrayList<ItemDelta<?>>();
		
		// Schema
		
		adjustSchemaForSimulatedCapabilities(resource, resourceSchema);
		
		ContainerDelta<XmlSchemaType> schemaContainerDelta = createSchemaUpdateDelta(resource, resourceSchema);
		modifications.add(schemaContainerDelta);
		
		
		// Capabilities
		
		Collection<Object> capabilities = null;
		try {

			capabilities = connector.fetchCapabilities(result);

		} catch (GenericFrameworkException ex) {
			throw new GenericConnectorException("Generic error in connector " + connector + ": "
					+ ex.getMessage(), ex);
		}
		
		CapabilitiesType capType = resourceType.getCapabilities();
		if (capType == null) {
			capType = new CapabilitiesType();
			resourceType.setCapabilities(capType);
		}
		
		if (capabilities != null) {
			CapabilityCollectionType nativeCapType = new CapabilityCollectionType();
			capType.setNative(nativeCapType);
			nativeCapType.getAny().addAll(capabilities);
		}
		CachingMetadataType cachingMetadata = MiscSchemaUtil.generateCachingMetadata();
		capType.setCachingMetadata(cachingMetadata);
		
		ObjectDelta<ResourceType> capabilitiesReplaceDelta = ObjectDelta.createModificationReplaceProperty(ResourceType.class, resource.getOid(), 
				ResourceType.F_CAPABILITIES, prismContext, capType);
		
		modifications.addAll((Collection<? extends ItemDelta<?>>) capabilitiesReplaceDelta.getModifications());
		
		// We have successfully fetched the resource schema. Therefore the resource must be up.
		modifications.add(createResourceAvailabilityStatusDelta(resource, AvailabilityStatusType.UP));

        try {
			repositoryService.modifyObject(ResourceType.class, resource.getOid(), modifications, result);
        } catch (ObjectAlreadyExistsException ex) {
        	// This should not happen
            throw new SystemException(ex);
        }

	}
	
	private ContainerDelta<XmlSchemaType> createSchemaUpdateDelta(PrismObject<ResourceType> resource, ResourceSchema resourceSchema) throws SchemaException {
		Document xsdDoc = null;
		try {
			// Convert to XSD
			LOGGER.trace("Serializing XSD resource schema for {} to DOM", resource);

			xsdDoc = resourceSchema.serializeToXsd();

			if (LOGGER.isTraceEnabled()) {
				LOGGER.trace("Serialized XSD resource schema for {}:\n{}",
						resource, DOMUtil.serializeDOMToString(xsdDoc));
			}

		} catch (SchemaException e) {
			throw new SchemaException("Error processing resource schema for "
					+ resource + ": " + e.getMessage(), e);
		}

		Element xsdElement = DOMUtil.getFirstChildElement(xsdDoc);
		if (xsdElement == null) {
			throw new SchemaException("No schema was generated for " + resource);
		}
		CachingMetadataType cachingMetadata = MiscSchemaUtil.generateCachingMetadata();

		// Store generated schema into repository (modify the original
		// Resource)
		LOGGER.info("Storing generated schema in resource {}", resource);

		ContainerDelta<XmlSchemaType> schemaContainerDelta = ContainerDelta.createDelta(prismContext,
				ResourceType.class, ResourceType.F_SCHEMA);
		PrismContainerValue<XmlSchemaType> cval = new PrismContainerValue<XmlSchemaType>();
		schemaContainerDelta.setValueToReplace(cval);
		PrismProperty<CachingMetadataType> cachingMetadataProperty = cval
				.createProperty(XmlSchemaType.F_CACHING_METADATA);
		cachingMetadataProperty.setRealValue(cachingMetadata);
		PrismProperty<Element> definitionProperty = cval.createProperty(XmlSchemaType.F_DEFINITION);
		ObjectTypeUtil.setXsdSchemaDefinition(definitionProperty, xsdElement);
		
		return schemaContainerDelta;
	}

	/**
	 * Apply proper definition (connector schema) to the resource.
	 */
	private void applyConnectorSchemaToResource(PrismObject<ResourceType> resource, OperationResult result)
			throws SchemaException, ObjectNotFoundException {
		
		ConnectorType connectorType = connectorTypeManager.getConnectorType(resource.asObjectable(), result);
		PrismContainerDefinition<ConnectorConfigurationType> configurationContainerDefintion = ConnectorTypeUtil
				.findConfigurationContainerDefintion(connectorType, prismContext);
		if (configurationContainerDefintion == null) {
			throw new SchemaException("No configuration container definition in " + connectorType);
		}
		PrismContainer<Containerable> configurationContainer = ResourceTypeUtil
				.getConfigurationContainer(resource);
		if (configurationContainer == null) {
			throw new SchemaException("No configuration container in " + resource);
		}
		configurationContainer.applyDefinition(configurationContainerDefintion, true);
	}

	public void testConnection(PrismObject<ResourceType> resource, OperationResult parentResult) {

		// === test INITIALIZATION ===

		OperationResult initResult = parentResult
				.createSubresult(ConnectorTestOperation.CONNECTOR_INITIALIZATION.getOperation());
		ConnectorInstance connector;
		try {

			connector = getConnectorInstance(resource, true, initResult);
			initResult.recordSuccess();
		} catch (ObjectNotFoundException e) {
			// The connector was not found. The resource definition is either
			// wrong or the connector is not
			// installed.
			initResult.recordFatalError("The connector was not found", e);
			return;
		} catch (SchemaException e) {
			initResult.recordFatalError("Schema error while dealing with the connector definition", e);
			return;
		} catch (RuntimeException e) {
			initResult.recordFatalError("Unexpected runtime error", e);
			return;
		} catch (CommunicationException e) {
			initResult.recordFatalError("Communication error", e);
			return;
		} catch (ConfigurationException e) {
			initResult.recordFatalError("Configuration error", e);
			return;
		}
		LOGGER.debug("Testing connection to the resource with oid {}", resource.getOid());

		// === test CONFIGURATION ===

		OperationResult configResult = parentResult
				.createSubresult(ConnectorTestOperation.CONFIGURATION_VALIDATION.getOperation());

		try {
			connector.configure(resource.findContainer(ResourceType.F_CONNECTOR_CONFIGURATION)
					.getValue(), configResult);
			configResult.recordSuccess();
		} catch (CommunicationException e) {
			configResult.recordFatalError("Communication error", e);
			return;
		} catch (GenericFrameworkException e) {
			configResult.recordFatalError("Generic error", e);
			return;
		} catch (SchemaException e) {
			configResult.recordFatalError("Schema error", e);
			return;
		} catch (ConfigurationException e) {
			configResult.recordFatalError("Configuration error", e);
			return;
		} catch (RuntimeException e) {
			configResult.recordFatalError("Unexpected runtime error", e);
			return;
		}

		// === test CONNECTION ===

		// delegate the main part of the test to the connector
		connector.test(parentResult);

		parentResult.computeStatus();
		if (!parentResult.isAcceptable()) {
			modifyResourceAvailabilityStatus(resource, AvailabilityStatusType.DOWN, parentResult);
			// No point in going on. Following tests will fail anyway, they will
			// just produce misleading
			// messages.
			return;
		} else {
			modifyResourceAvailabilityStatus(resource, AvailabilityStatusType.UP, parentResult);
		}

		// === test SCHEMA ===

		OperationResult schemaResult = parentResult.createSubresult(ConnectorTestOperation.CONNECTOR_SCHEMA
				.getOperation());

		ResourceSchema schema = null;
		try {
			// Try to fetch schema from the connector. The UCF will convert it
			// to Schema Processor
			// format, so it is already structured
			InternalMonitor.recordConnectorSchemaFetch();
			schema = connector.fetchResourceSchema(schemaResult);
		} catch (CommunicationException e) {
			schemaResult.recordFatalError("Communication error: " + e.getMessage(), e);
			return;
		} catch (GenericFrameworkException e) {
			schemaResult.recordFatalError("Generic error: " + e.getMessage(), e);
			return;
		} catch (ConfigurationException e) {
			schemaResult.recordFatalError("Configuration error: " + e.getMessage(), e);
			return;
		}

		if (schema == null || schema.isEmpty()) {
			// Resource does not support schema
			// If there is a static schema in resource definition this may still be OK
			try {
				schema = RefinedResourceSchema.getResourceSchema(resource, prismContext);
			} catch (SchemaException e) {
				schemaResult.recordFatalError(e);
				return;
			}
			
			if (schema == null || schema.isEmpty()) {
				schemaResult.recordFatalError("Connector does not support schema and no static schema available");
				return;
			}
		}

		// Invoke completeResource(). This will store the fetched schema to the
		// ResourceType
		// if there is no <schema> definition already. Therefore the
		// testResource() can be used to
		// generate the resource schema - until we have full schema caching
		// capability.
		try {
			completeResource(resource, schema, schemaResult);
		} catch (ObjectNotFoundException e) {
			schemaResult.recordFatalError(
					"Object not found (unexpected error, probably a bug): " + e.getMessage(), e);
			return;
		} catch (SchemaException e) {
			schemaResult.recordFatalError(
					"Schema processing error (probably connector bug): " + e.getMessage(), e);
			return;
		} catch (CommunicationException e) {
			schemaResult.recordFatalError("Communication error: " + e.getMessage(), e);
			return;
		} catch (ConfigurationException e) {
			schemaResult.recordFatalError("Configuration error: " + e.getMessage(), e);
			return;
		}

		schemaResult.recordSuccess();

		// TODO: connector sanity (e.g. at least one account type, identifiers
		// in schema, etc.)

	}
	
	public void modifyResourceAvailabilityStatus(PrismObject<ResourceType> resource, AvailabilityStatusType status, OperationResult result){
			ResourceType resourceType = resource.asObjectable();
			
			if (resourceType.getOperationalState() == null || resourceType.getOperationalState().getLastAvailabilityStatus() == null || resourceType.getOperationalState().getLastAvailabilityStatus() != status) {
				List<PropertyDelta<?>> modifications = new ArrayList<PropertyDelta<?>>();
				PropertyDelta<?> statusDelta = createResourceAvailabilityStatusDelta(resource, status);
				modifications.add(statusDelta);
				
				
				try{
					repositoryService.modifyObject(ResourceType.class, resourceType.getOid(), modifications, result);
				} catch(SchemaException ex){
					throw new SystemException(ex);
				} catch(ObjectAlreadyExistsException ex){
					throw new SystemException(ex);
				} catch(ObjectNotFoundException ex){
					throw new SystemException(ex);
				}
			}
			if (resourceType.getOperationalState() == null){
				OperationalStateType operationalState = new OperationalStateType();
				operationalState.setLastAvailabilityStatus(status);
				resourceType.setOperationalState(operationalState);
			} else{
				resourceType.getOperationalState().setLastAvailabilityStatus(status);
			}
		}
	
	private PropertyDelta<?> createResourceAvailabilityStatusDelta(PrismObject<ResourceType> resource, AvailabilityStatusType status) {
		PropertyDelta<?> statusDelta = PropertyDelta.createModificationReplaceProperty(OperationalStateType.F_LAST_AVAILABILITY_STATUS, resource.getDefinition(), status);
		statusDelta.setParentPath(new ItemPath(ResourceType.F_OPERATIONAL_STATE));
		return statusDelta;
	}

	/**
	 * Adjust scheme with respect to capabilities. E.g. disable attributes that
	 * are used for special purpose (such as account activation simulation).
	 */
	private void adjustSchemaForSimulatedCapabilities(PrismObject<ResourceType> resource, ResourceSchema resourceSchema) {
		ResourceType resourceType = resource.asObjectable();
		if (resourceType.getCapabilities() == null || resourceType.getCapabilities().getConfigured() == null) {
			return;
		}
		ActivationCapabilityType activationCapability = CapabilityUtil.getCapability(resourceType
				.getCapabilities().getConfigured().getAny(), ActivationCapabilityType.class);
		if (activationCapability != null && activationCapability.getEnableDisable() != null) {
			QName attributeName = activationCapability.getEnableDisable().getAttribute();
			Boolean ignore = activationCapability.getEnableDisable().isIgnoreAttribute();
			if (attributeName != null) {
				// The attribute used for enable/disable simulation should be ignored in the schema
				// otherwise strange things may happen, such as changing the same attribute both from
				// activation/enable and from the attribute using its native name.
				for (ObjectClassComplexTypeDefinition objectClassDefinition : resourceSchema
						.getDefinitions(ObjectClassComplexTypeDefinition.class)) {
					ResourceAttributeDefinition attributeDefinition = objectClassDefinition
							.findAttributeDefinition(attributeName);
					if (attributeDefinition != null) {
						if (ignore != null && !ignore.booleanValue()) {
							attributeDefinition.setIgnored(false);
						} else {
							attributeDefinition.setIgnored(true);
						}
					} else {
						// simulated activation attribute points to something that is not in the schema
						// technically, this is an error. But it looks to be quite common in connectors.
						// The enable/disable is using operational attributes that are not exposed in the
						// schema, but they work if passed to the connector.
						// Therefore we don't want to break anything. We could log an warning here, but the
						// warning would be quite frequent. Maybe a better place to warn user would be import
						// of the object.
						LOGGER.debug("Simulated activation attribute "
								+ attributeName
								+ " for objectclass "
								+ objectClassDefinition.getTypeName()
								+ " in "
								+ resource
								+ " does not exist in the resource schema. This may work well, but it is not clean. Connector exposing such schema should be fixed.");
					}
				}
			}
		}
	}

	public ResourceSchema getResourceSchema(ResourceType resource, OperationResult parentResult) throws SchemaException, CommunicationException,
			ConfigurationException {

		ResourceSchema schema = null;
		try {

			// Make sure that the schema is retrieved from the resource
			// this will also retrieve the schema from cache and/or parse it if
			// needed
			ResourceType completeResource = completeResource(resource.asPrismObject(), null, parentResult).asObjectable();
			schema = RefinedResourceSchema.getResourceSchema(completeResource, prismContext);

		} catch (SchemaException e) {
			parentResult.recordFatalError("Unable to parse resource schema: " + e.getMessage(), e);
			throw new SchemaException("Unable to parse resource schema: " + e.getMessage(), e);
		} catch (ObjectNotFoundException e) {
			// this really should not happen
			parentResult.recordFatalError("Unexpected ObjectNotFoundException: " + e.getMessage(), e);
			throw new SystemException("Unexpected ObjectNotFoundException: " + e.getMessage(), e);
		} catch (ConfigurationException e) {
			parentResult.recordFatalError("Unable to parse resource schema: " + e.getMessage(), e);
			throw new ConfigurationException("Unable to parse resource schema: " + e.getMessage(), e);
		}
		
		if (schema == null) {
			return null;
		}

		checkSchema(schema);

		return schema;
	}

	private void checkSchema(PrismSchema schema) throws SchemaException {
		// This is resource schema, it should contain only
		// ResourceObjectDefintions
		for (Definition def : schema.getDefinitions()) {
			if (def instanceof ComplexTypeDefinition) {
				// This is OK
			} else if (def instanceof ResourceAttributeContainerDefinition) {
				checkResourceObjectDefinition((ResourceAttributeContainerDefinition) def);
			} else {
				throw new SchemaException("Unexpected definition in resource schema: " + def);
			}
		}
	}

	private void checkResourceObjectDefinition(ResourceAttributeContainerDefinition rod)
			throws SchemaException {
		for (ItemDefinition def : rod.getDefinitions()) {
			if (!(def instanceof ResourceAttributeDefinition)) {
				throw new SchemaException("Unexpected definition in resource schema object " + rod + ": "
						+ def);
			}
		}
	}

	private ConnectorInstance getConnectorInstance(PrismObject<ResourceType> resource, boolean forceFresh, OperationResult parentResult)
			throws ObjectNotFoundException, SchemaException, CommunicationException, ConfigurationException {
		return connectorTypeManager.getConfiguredConnectorInstance(resource, forceFresh, parentResult);
	}

	public void applyDefinition(ObjectDelta<ResourceType> delta, OperationResult objectResult) throws SchemaException, ObjectNotFoundException, CommunicationException, ConfigurationException {
		
		if (delta.isAdd()) {
			PrismObject<ResourceType> resource = delta.getObjectToAdd();
			applyConnectorSchemaToResource(resource, objectResult);
			return;
			
		} else if (delta.isModify()) {
			// Go on
			
		} else {
			return;
		}
		
		String resourceOid = delta.getOid();
		Validate.notNull(resourceOid, "Resource oid not specified in the object delta. Could not apply definition.");
		PrismObject<ResourceType>resource = getResource(resourceOid, objectResult);
		ResourceType resourceType = resource.asObjectable();
//		ResourceType resourceType = completeResource(resource.asObjectable(), null, objectResult);
		//TODO TODO TODO FIXME FIXME FIXME copied from ObjectImprted..union this two cases
		PrismContainer<Containerable> configurationContainer = ResourceTypeUtil.getConfigurationContainer(resourceType);
        if (configurationContainer == null || configurationContainer.isEmpty()) {
            // Nothing to check
            objectResult.recordWarning("The resource has no configuration");
            return;
        }

        // Check the resource configuration. The schema is in connector, so fetch the connector first
        String connectorOid = resourceType.getConnectorRef().getOid();
        if (StringUtils.isBlank(connectorOid)) {
            objectResult.recordFatalError("The connector reference (connectorRef) is null or empty");
            return;
        }

        PrismObject<ConnectorType> connector = null;
        ConnectorType connectorType = null;
        try {
            connector = repositoryService.getObject(ConnectorType.class, connectorOid, objectResult);
            connectorType = connector.asObjectable();
        } catch (ObjectNotFoundException e) {
            // No connector, no fun. We can't check the schema. But this is referential integrity problem.
            // Mark the error ... there is nothing more to do
            objectResult.recordFatalError("Connector (OID:" + connectorOid + ") referenced from the resource is not in the repository", e);
            return;
        } catch (SchemaException e) {
            // Probably a malformed connector. To be kind of robust, lets allow the import.
            // Mark the error ... there is nothing more to do
            objectResult.recordPartialError("Connector (OID:" + connectorOid + ") referenced from the resource has schema problems: " + e.getMessage(), e);
            LOGGER.error("Connector (OID:{}) referenced from the imported resource \"{}\" has schema problems: {}", new Object[]{connectorOid, resourceType.getName(), e.getMessage(), e});
            return;
        }
        
        Element connectorSchemaElement = ConnectorTypeUtil.getConnectorXsdSchema(connector);
        PrismSchema connectorSchema = null;
        if (connectorSchemaElement == null) {
        	// No schema to validate with
        	return;
        }
		try {
			connectorSchema = PrismSchema.parse(connectorSchemaElement, "schema for " + connector, prismContext);
		} catch (SchemaException e) {
			objectResult.recordFatalError("Error parsing connector schema for " + connector + ": "+e.getMessage(), e);
			return;
		}
        QName configContainerQName = new QName(connectorType.getNamespace(), ResourceType.F_CONNECTOR_CONFIGURATION.getLocalPart());
		PrismContainerDefinition<?> configContainerDef = connectorSchema.findContainerDefinitionByElementName(configContainerQName);
		if (configContainerDef == null) {
			objectResult.recordFatalError("Definition of configuration container " + configContainerQName + " not found in the schema of of " + connector);
            return;
		}
        
        try {
			configurationContainer.applyDefinition(configContainerDef);
		} catch (SchemaException e) {
			objectResult.recordFatalError("Configuration error in " + resource + ": "+e.getMessage(), e);
            return;
		}
     
        resourceType.asPrismObject().findContainer(ResourceType.F_CONNECTOR_CONFIGURATION).applyDefinition(configContainerDef);
 
        for (ItemDelta<?> itemDelta : delta.getModifications()){
        	if (itemDelta.getParentPath() == null){
        		LOGGER.trace("No parent path defined for item delta {}", itemDelta);
        		continue;
        	}
        	
        	QName first = ItemPath.getName(itemDelta.getParentPath().first());
        	
        	if (first == null){
        		continue;
        	}
        	
        	if (itemDelta.getDefinition() == null && (ResourceType.F_CONNECTOR_CONFIGURATION.equals(first) || ResourceType.F_SCHEMA.equals(first))){
        		ItemPath path = itemDelta.getPath().rest();
        		ItemDefinition itemDef = configContainerDef.findItemDefinition(path);
				itemDelta.applyDefinition(itemDef);
        		
        	}
        }
	}

	

	public void applyDefinition(PrismObject<ResourceType> resource, OperationResult parentResult) throws ObjectNotFoundException, SchemaException, CommunicationException, ConfigurationException {
		applyConnectorSchemaToResource(resource, parentResult);
	}
}
