/*
 * Copyright (c) 2010-2014 Evolveum
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.evolveum.midpoint.model.impl.scripting.actions;

import com.evolveum.midpoint.model.impl.scripting.Data;
import com.evolveum.midpoint.model.impl.scripting.ExecutionContext;
import com.evolveum.midpoint.model.api.ScriptExecutionException;
import com.evolveum.midpoint.prism.*;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.delta.ReferenceDelta;
import com.evolveum.midpoint.prism.query.ObjectQuery;
import com.evolveum.midpoint.prism.query.builder.QueryBuilder;
import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.util.exception.CommunicationException;
import com.evolveum.midpoint.util.exception.ConfigurationException;
import com.evolveum.midpoint.util.exception.ObjectNotFoundException;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.util.exception.SecurityViolationException;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ConnectorType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ResourceType;
import com.evolveum.midpoint.xml.ns._public.model.scripting_3.ActionExpressionType;

import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author mederly
 */
@Component
public class TestResourceExecutor extends BaseActionExecutor {

    private static final Trace LOGGER = TraceManager.getTrace(TestResourceExecutor.class);

    private static final String NAME = "test-resource";

    @PostConstruct
    public void init() {
        scriptingExpressionEvaluator.registerActionExecutor(NAME, this);
    }

    @Override
    public Data execute(ActionExpressionType expression, Data input, ExecutionContext context, OperationResult result) throws ScriptExecutionException {

        Data output = Data.createEmpty();

        for (PrismValue value: input.getData()) {
            if (value instanceof PrismObjectValue && ((PrismObjectValue) value).asObjectable() instanceof ResourceType) {
                PrismObject<ResourceType> resourceTypePrismObject = ((PrismObjectValue) value).asPrismObject();
                ResourceType resourceType = resourceTypePrismObject.asObjectable();
                long started = operationsHelper.recordStart(context, resourceType);
                OperationResult testResult;
                try {
                    testResult = modelService.testResource(resourceTypePrismObject.getOid(), context.getTask());
                    operationsHelper.recordEnd(context, resourceType, started, null);
                } catch (ObjectNotFoundException|RuntimeException e) {
                    operationsHelper.recordEnd(context, resourceType, started, e);
                    throw new ScriptExecutionException("Couldn't test resource " + resourceTypePrismObject, e);
                }
                result.addSubresult(testResult);
                context.println("Tested " + resourceTypePrismObject + ": " + testResult.getStatus());
                output.addItem(operationsHelper.getObject(ResourceType.class, resourceTypePrismObject.getOid(), false, context, result));
            } else {
                throw new ScriptExecutionException("Couldn't test a resource, because input is not a PrismObject<ResourceType>: " + value.toString());
            }
        }
        return output;
    }

    private void rebindConnectors(Set<ConnectorType> newConnectors, ExecutionContext context, OperationResult result) throws ScriptExecutionException {
        Map<String,String> rebindMap = new HashMap<>();
        for (ConnectorType connectorType : newConnectors) {
            determineConnectorMappings(rebindMap, connectorType, context, result);
        }
        LOGGER.trace("Connector rebind map: {}", rebindMap);
        rebindResources(rebindMap, context, result);
    }

    private void rebindResources(Map<String, String> rebindMap, ExecutionContext context, OperationResult result) throws ScriptExecutionException {
        List<PrismObject<ResourceType>> resources;
        try {
            resources = modelService.searchObjects(ResourceType.class, null, null, null, result);
        } catch (SchemaException|ConfigurationException|ObjectNotFoundException|CommunicationException|SecurityViolationException e) {
            throw new ScriptExecutionException("Couldn't list resources: " + e.getMessage(), e);
        }
        for (PrismObject<ResourceType> resource : resources) {
            if (resource.asObjectable().getConnectorRef() != null) {
                String connectorOid = resource.asObjectable().getConnectorRef().getOid();
                String newOid = rebindMap.get(connectorOid);
                if (newOid != null) {
                    String msg = "resource " + resource + " from connector " + connectorOid + " to new one: " + newOid;
                    LOGGER.info("Rebinding " + msg);
                    ReferenceDelta refDelta = ReferenceDelta.createModificationReplace(ResourceType.F_CONNECTOR_REF, resource.getDefinition(), newOid);
                    ObjectDelta<ResourceType> objDelta = ObjectDelta.createModifyDelta(resource.getOid(), refDelta, ResourceType.class, prismContext);
                    operationsHelper.applyDelta(objDelta, context, result);
                    context.println("Rebound " + msg);
                }
            }
        }
    }

    private void determineConnectorMappings(Map<String,String> rebindMap, ConnectorType connectorType, ExecutionContext context, OperationResult result) throws ScriptExecutionException {

        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Finding obsolete versions for connector: {}", connectorType.asPrismObject().debugDump());
        }
        ObjectQuery query = QueryBuilder.queryFor(ConnectorType.class, prismContext)
                .item(SchemaConstants.C_CONNECTOR_FRAMEWORK).eq(connectorType.getFramework())
                .and().item(SchemaConstants.C_CONNECTOR_CONNECTOR_TYPE).eq(connectorType.getConnectorType())
                .build();
        List<PrismObject<ConnectorType>> foundConnectors;
        try {
            foundConnectors = modelService.searchObjects(ConnectorType.class, query, null, null, result);
        } catch (SchemaException|ConfigurationException|ObjectNotFoundException|CommunicationException|SecurityViolationException e) {
            throw new ScriptExecutionException("Couldn't get connectors of type: " + connectorType.getConnectorType() + ": " + e.getMessage(), e);
        }

        for (PrismObject<ConnectorType> foundConnector : foundConnectors) {
            ConnectorType foundConnectorType = foundConnector.asObjectable();
            if (connectorType.getConnectorHostRef().equals(foundConnectorType.getConnectorHostRef()) &&
                    foundConnectorType.getConnectorVersion() != null &&
                    !foundConnectorType.getConnectorVersion().equals(connectorType.getConnectorVersion())) {
                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace("Found obsolete connector: {}", foundConnectorType.asPrismObject().debugDump());
                }
                rebindMap.put(foundConnectorType.getOid(), connectorType.getOid());
            }
        }
    }
}
