/*
 * Copyright (c) 2010-2015 Evolveum
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
package com.evolveum.midpoint.web.component.prism;

import com.evolveum.midpoint.prism.PrismReference;
import com.evolveum.midpoint.prism.PrismReferenceDefinition;
import com.evolveum.midpoint.prism.PrismReferenceValue;
import com.evolveum.midpoint.util.DebugUtil;
import com.evolveum.midpoint.util.PrettyPrinter;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class ReferenceWrapper extends PropertyOrReferenceWrapper<PrismReference, PrismReferenceDefinition> implements Serializable {

	private static final long serialVersionUID = 3132143219403214903L;

	public ReferenceWrapper(ContainerWrapper container, PrismReference reference, boolean readonly, ValueStatus status) {
		super(container, reference, readonly, status);
	}

	public List<ValueWrapper> getValues() {
		if (values == null) {
			values = createValues();
		}
		return values;
	}

	private List<ValueWrapper> createValues() {
		List<ValueWrapper> values = new ArrayList<ValueWrapper>();

		for (PrismReferenceValue prismValue : item.getValues()) {
			values.add(new ValueWrapper(this, prismValue, prismValue, ValueStatus.NOT_CHANGED));
		}

		int minOccurs = getDefinition().getMinOccurs();
		while (values.size() < minOccurs) {
			values.add(createAddedValue());
		}

		if (values.isEmpty()) {
			values.add(createAddedValue());
		}

		return values;
	}

	@Override
	public ValueWrapper createAddedValue() {
		PrismReferenceValue prv = new PrismReferenceValue();
		ValueWrapper wrapper = new ValueWrapper(this, prv, ValueStatus.ADDED);
		return wrapper;
	}

	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append(getDisplayName());
		builder.append(", ");
		builder.append(status);
		builder.append("\n");
		for (ValueWrapper wrapper : getValues()) {
			builder.append("\t");
			builder.append(wrapper.toString());
			builder.append("\n");
		}
		return builder.toString();
	}

	@Override
	public String debugDump() {
		return debugDump(0);
	}

	@Override
	public String debugDump(int indent) {
		StringBuilder sb = new StringBuilder();
		DebugUtil.indentDebugDump(sb, indent);
		sb.append("ReferenceWrapper: ").append(PrettyPrinter.prettyPrint(getName())).append("\n");
		DebugUtil.debugDumpWithLabel(sb, "displayName", displayName, indent+1);
		sb.append("\n");
		DebugUtil.debugDumpWithLabel(sb, "status", status == null?null:status.toString(), indent+1);
		sb.append("\n");
		DebugUtil.debugDumpWithLabel(sb, "readonly", readonly, indent+1);
		sb.append("\n");
		DebugUtil.debugDumpWithLabel(sb, "itemDefinition", itemDefinition == null?null:itemDefinition.toString(), indent+1);
		sb.append("\n");
		DebugUtil.debugDumpWithLabel(sb, "reference", item, indent+1);
		sb.append("\n");
		DebugUtil.debugDumpWithLabel(sb, "values", values, indent+1);
		return sb.toString();
	}

}
