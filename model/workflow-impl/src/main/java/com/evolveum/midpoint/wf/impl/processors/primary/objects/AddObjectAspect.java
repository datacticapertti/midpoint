/*
 * Copyright (c) 2010-2015 Evolveum
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

package com.evolveum.midpoint.wf.impl.processors.primary.objects;

import com.evolveum.midpoint.model.api.context.ModelContext;
import com.evolveum.midpoint.model.impl.lens.LensFocusContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.delta.ChangeType;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.schema.util.OidUtil;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.wf.impl.processes.itemApproval.ApprovalRequest;
import com.evolveum.midpoint.wf.impl.processes.itemApproval.ApprovalRequestImpl;
import com.evolveum.midpoint.schema.ObjectTreeDeltas;
import com.evolveum.midpoint.wf.impl.processors.primary.PcpChildWfTaskCreationInstruction;
import com.evolveum.midpoint.wf.impl.processors.primary.aspect.BasePrimaryChangeAspect;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.PcpAspectConfigurationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.PrimaryChangeProcessorConfigurationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import org.apache.commons.lang.Validate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Change aspect that manages addition of an object.
 *
 * @author mederly
 */
public abstract class AddObjectAspect<T extends ObjectType> extends BasePrimaryChangeAspect {

    private static final Trace LOGGER = TraceManager.getTrace(AddObjectAspect.class);

	//region ------------------------------------------------------------ Things that execute on request arrival

    protected abstract Class<T> getObjectClass();
    protected abstract String getObjectLabel(T object);

    @Override
    public List<PcpChildWfTaskCreationInstruction> prepareTasks(ModelContext<?> modelContext,
                                                                               PrimaryChangeProcessorConfigurationType wfConfigurationType,
                                                                               ObjectTreeDeltas objectTreeDeltas,
                                                                               Task taskFromModel, OperationResult result) throws SchemaException {
        PcpAspectConfigurationType config = primaryChangeAspectHelper.getPcpAspectConfigurationType(wfConfigurationType, this);
        if (config == null) {
            return null;            // this should not occur (because this aspect is not enabled by default), but check it just to be sure
        }
        if (!primaryChangeAspectHelper.isRelatedToType(modelContext, getObjectClass()) || objectTreeDeltas.getFocusChange() == null) {
            return null;
        }
        List<ApprovalRequest<T>> approvalRequestList = getApprovalRequests(modelContext, config, objectTreeDeltas.getFocusChange(), result);
        if (approvalRequestList == null || approvalRequestList.isEmpty()) {
            return null;
        }
        return prepareJobCreateInstructions(modelContext, taskFromModel, result, approvalRequestList);
    }

    private List<ApprovalRequest<T>> getApprovalRequests(ModelContext<?> modelContext, PcpAspectConfigurationType config,
                                                         ObjectDelta<? extends ObjectType> change, OperationResult result) {
        if (change.getChangeType() != ChangeType.ADD) {
            return null;
        }
        T objectType = (T) change.getObjectToAdd().asObjectable().clone();
        if (objectType.getOid() == null) {
            String newOid = OidUtil.generateOid();
            objectType.setOid(newOid);
            ((LensFocusContext<?>) modelContext.getFocusContext()).setOid(newOid);
        }
        change.setObjectToAdd(null);            // make the change empty
        return Arrays.asList(createApprovalRequest(config, objectType));
    }

    // creates an approval request for a given role create request
    private ApprovalRequest<T> createApprovalRequest(PcpAspectConfigurationType config, T objectType) {

        return new ApprovalRequestImpl(objectType, config, prismContext);
    }

    private List<PcpChildWfTaskCreationInstruction> prepareJobCreateInstructions(ModelContext<?> modelContext, Task taskFromModel, OperationResult result,
                                                                              List<ApprovalRequest<T>> approvalRequestList) throws SchemaException {
        List<PcpChildWfTaskCreationInstruction> instructions = new ArrayList<>();

        for (ApprovalRequest<T> approvalRequest : approvalRequestList) {     // there should be just one

            assert approvalRequest.getPrismContext() != null;

            LOGGER.trace("Approval request = {}", approvalRequest);

            T objectToAdd = approvalRequest.getItemToApprove();
            Validate.notNull(objectToAdd);
            String objectLabel = getObjectLabel(objectToAdd);

            PrismObject<UserType> requester = baseModelInvocationProcessingHelper.getRequester(taskFromModel, result);

            String approvalTaskName = "Approve creating " + objectLabel;

            // create a JobCreateInstruction for a given change processor (primaryChangeProcessor in this case)
            PcpChildWfTaskCreationInstruction instruction =
                    PcpChildWfTaskCreationInstruction.createItemApprovalInstruction(getChangeProcessor(), approvalTaskName, approvalRequest);

            // set some common task/process attributes
            instruction.prepareCommonAttributes(this, modelContext, requester);

            // prepare and set the delta that has to be approved
            ObjectDelta<? extends ObjectType> delta = assignmentToDelta(modelContext);
            instruction.setDeltasToProcess(delta);

            instruction.setObjectRef(modelContext, result);
            instruction.setTargetRef(null, result);

            // set the names of midPoint task and activiti process instance
            String andExecuting = instruction.isExecuteApprovedChangeImmediately() ? "and execution " : "";
            instruction.setTaskName("Approval " + andExecuting + "of creation of " + objectLabel);
            instruction.setProcessInstanceName("Creating " + objectLabel);

            // setup general item approval process
            itemApprovalProcessInterface.prepareStartInstruction(instruction);

            instructions.add(instruction);
        }
        return instructions;
    }

    private ObjectDelta<? extends ObjectType> assignmentToDelta(ModelContext<? extends ObjectType> modelContext) {
        return modelContext.getFocusContext().getPrimaryDelta();
    }

    //endregion

    //region ------------------------------------------------------------ Things that execute when item is being approved

    //endregion
}