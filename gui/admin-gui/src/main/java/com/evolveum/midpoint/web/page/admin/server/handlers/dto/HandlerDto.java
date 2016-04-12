/*
 * Copyright (c) 2010-2016 Evolveum
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

package com.evolveum.midpoint.web.page.admin.server.handlers.dto;

import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.web.page.admin.server.PageTaskEdit;
import com.evolveum.midpoint.web.page.admin.server.dto.TaskDto;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;

import java.io.Serializable;

/**
 * @author mederly
 */
public class HandlerDto implements Serializable {
	public static final String F_OBJECT_REF_NAME = "objectRefName";
	public static final String F_OBJECT_REF = "objectRef";

	protected TaskDto taskDto;

	public HandlerDto(TaskDto taskDto) {
		this.taskDto = taskDto;
	}

	public TaskDto getTaskDto() {
		return taskDto;
	}

	public String getObjectRefName() {
		return taskDto.getObjectRefName();
	}

	public ObjectReferenceType getObjectRef() {
		return taskDto.getObjectRef();
	}

	public void updateTask(Task existingTask, PageTaskEdit parentPage) throws SchemaException {
	}
}