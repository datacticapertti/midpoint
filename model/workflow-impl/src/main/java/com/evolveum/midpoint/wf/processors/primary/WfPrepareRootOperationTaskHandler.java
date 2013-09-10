/*
 * Copyright (c) 2010-2013 Evolveum
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.evolveum.midpoint.wf.processors.primary;

import com.evolveum.midpoint.model.lens.LensContext;
import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.task.api.*;
import com.evolveum.midpoint.task.api.TaskRunResult.TaskRunResultStatus;
import com.evolveum.midpoint.util.exception.*;
import com.evolveum.midpoint.util.logging.LoggingUtils;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.wf.WfConfiguration;
import com.evolveum.midpoint.wf.jobs.WfTaskUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;

/**
 * This handler takes all changes from child tasks and puts them into model context of this task.
 *
 * @author mederly
 */
@Component
public class WfPrepareRootOperationTaskHandler implements TaskHandler {

    // should be available only within the context of primary change processor
	static final String HANDLER_URI = "http://midpoint.evolveum.com/xml/ns/public/workflow/prepare-root-operation/handler-2";

    private static final Trace LOGGER = TraceManager.getTrace(WfPrepareRootOperationTaskHandler.class);

    @Autowired
    private TaskManager taskManager;

    @Autowired
    private WfTaskUtil wfTaskUtil;

    @Autowired
    private WfConfiguration wfConfiguration;

    @PostConstruct
    public void init() {
        LOGGER.trace("Registering with taskManager as a handler for " + HANDLER_URI);
        taskManager.registerHandler(HANDLER_URI, this);
    }

	@Override
	public TaskRunResult run(Task task) {

        TaskRunResultStatus status = TaskRunResultStatus.FINISHED;

        try {

            OperationResult result = task.getResult();

            List<Task> children = task.listSubtasks(result);

            LensContext rootContext = (LensContext) wfTaskUtil.retrieveModelContext(task, result);

            boolean changed = false;
            for (Task child : children) {

                if (child.getExecutionStatus() != TaskExecutionStatus.CLOSED) {
                    throw new IllegalStateException("Child task " + child + " is not in CLOSED state; its state is " + child.getExecutionStatus());
                }

                if (wfTaskUtil.hasModelContext(child)) {
                    if (LOGGER.isTraceEnabled()) {
                        LOGGER.trace("Child task {} has model context present - skipping fetching deltas from it.");
                    }
                } else {
                    List<ObjectDelta<Objectable>> deltas = wfTaskUtil.retrieveResultingDeltas(child);
                    if (LOGGER.isTraceEnabled()) {
                        LOGGER.trace("Task {} returned {} deltas", child, deltas.size());
                    }
                    for (ObjectDelta delta : deltas) {
                        if (LOGGER.isTraceEnabled()) {
                            LOGGER.trace("Adding delta from task {} to root model context; delta = {}", child, delta.debugDump(0));
                        }
                        rootContext.getFocusContext().addPrimaryDelta(delta);
                        changed = true;
                    }
                }
            }

            if (changed) {
                wfTaskUtil.storeModelContext(task, rootContext);
                task.savePendingModifications(result);
            }

        } catch (SchemaException e) {
            LoggingUtils.logException(LOGGER, "Couldn't aggregate resulting deltas from child workflow-monitoring tasks due to schema exception", e);
            status = TaskRunResultStatus.PERMANENT_ERROR;
        } catch (ObjectNotFoundException e) {
            LoggingUtils.logException(LOGGER, "Couldn't aggregate resulting deltas from child workflow-monitoring tasks", e);
            status = TaskRunResultStatus.PERMANENT_ERROR;
        } catch (ObjectAlreadyExistsException e) {
            LoggingUtils.logException(LOGGER, "Couldn't aggregate resulting deltas from child workflow-monitoring tasks", e);
            status = TaskRunResultStatus.PERMANENT_ERROR;
        } catch (CommunicationException e) {
            LoggingUtils.logException(LOGGER, "Couldn't aggregate resulting deltas from child workflow-monitoring tasks", e);
            status = TaskRunResultStatus.TEMPORARY_ERROR;
        } catch (ConfigurationException e) {
            LoggingUtils.logException(LOGGER, "Couldn't aggregate resulting deltas from child workflow-monitoring tasks", e);
            status = TaskRunResultStatus.PERMANENT_ERROR;
        }

        TaskRunResult runResult = new TaskRunResult();
		runResult.setRunResultStatus(status);
		return runResult;
	}
	
	@Override
	public Long heartbeat(Task task) {
		return null;		// null - as *not* to record progress (which would overwrite operationResult!)
	}

	@Override
	public void refreshStatus(Task task) {
	}

    @Override
    public String getCategoryName(Task task) {
        return TaskCategory.WORKFLOW;
    }

    @Override
    public List<String> getCategoryNames() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

}