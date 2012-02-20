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

package com.evolveum.midpoint.prism;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.xml.namespace.QName;

import com.evolveum.midpoint.util.exception.SystemException;
import org.w3c.dom.Node;

import com.evolveum.midpoint.prism.delta.ChangeType;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.delta.PropertyDelta;
import com.evolveum.midpoint.util.DOMUtil;
import com.evolveum.midpoint.util.MiscUtil;
import com.evolveum.midpoint.util.exception.SchemaException;

/**
 * Common supertype for all identity objects. Defines basic properties that each
 * object must have to live in our system (identifier, name).
 *
 * Objects consists of identifier and name (see definition below) and a set of
 * properties represented as XML elements in the object's body. The attributes
 * are represented as first-level XML elements (tags) of the object XML
 * representation and may be also contained in other tags (e.g. extension,
 * attributes). The QName (namespace and local name) of the element holding the
 * property is considered to be a property name.
 *
 * This class is named MidPointObject instead of Object to avoid confusion with
 * java.lang.Object.
 *
 * @author Radovan Semancik
 *
 */
public class PrismObject<T extends Objectable> extends PrismContainer {

	protected String oid;
	protected String version;
	protected Class<T> compileTimeClass;

	public PrismObject(QName name, Class<T> compileTimeClass) {
		super(name);
		this.compileTimeClass = compileTimeClass;
	}

	public PrismObject(QName name, PrismObjectDefinition definition, PrismContext prismContext) {
		super(name, definition, prismContext);
	}

	/**
	 * Returns Object ID (OID).
	 *
	 * May return null if the object does not have an OID.
	 *
	 * @return Object ID (OID)
	 */
	public String getOid() {
		return oid;
	}

	public void setOid(String oid) {
		this.oid = oid;
	}

	public String getVersion() {
		return version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

	@Override
	public PrismObjectDefinition<T> getDefinition() {
		return (PrismObjectDefinition<T>) super.getDefinition();
	}

	public Class<T> getCompileTimeClass() {
		if (this.compileTimeClass != null) {
			return compileTimeClass;
		}
		if (getDefinition() != null) {
			return getDefinition().getCompileTimeClass();
		}
		return null;
	}
	
	/**
	 * Returns true if this object can represent specified compile-time class.
	 * I.e. this object can be presented in the compile-time form that is an
	 * instance of a specified class.
	 */
	public boolean canRepresent(Class<?> compileTimeClass) {
		return (compileTimeClass.isAssignableFrom(getCompileTimeClass()));
	}

	public T asObjectable() {
        try {
            Class<T> clazz = getCompileTimeClass();
            if (clazz == null) {
                throw new SystemException("Unknown compile time class of this prism object '" + getName() + "'.");
            }
            T object = clazz.newInstance();
            object.setContainer(this);

            return (T) object;
        } catch (SystemException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new SystemException("Couldn't create jaxb object instance of '" + getCompileTimeClass() + "'.");
        }
	}

	public PrismContainer getExtension() {
		return getValue().findItem(new QName(getName().getNamespaceURI(), PrismConstants.EXTENSION_LOCAL_NAME), PrismContainer.class);
	}
	
	@Override
	public void applyDefinition(ItemDefinition definition) {
    	if (!(definition instanceof PrismObjectDefinition)) {
    		throw new IllegalArgumentException("Cannot apply "+definition+" to object");
    	}
    	super.applyDefinition(definition);
	}

	@Override
	public <I extends Item> I findItem(PropertyPath path, Class<I> type) {
		return findCreateItem(path, type, false);
	}

	@Override
	<I extends Item> I findCreateItem(PropertyPath path, Class<I> type, boolean create) {
		// Objects are only a single-valued containers. The path of the object itself is "empty".
		// Fix this special behavior here.
		PropertyPathSegment first = path.first();
		Item subitem = getValue().findCreateItem(first.getName(), Item.class, create);
		if (subitem == null) {
			return null;
		}
		if (subitem instanceof PrismContainer) {
			return ((PrismContainer)subitem).findCreateItem(path, type, create);
		} else if (type.isAssignableFrom(subitem.getClass())){
			return (I) subitem;
		} else {
			if (create) {
				throw new IllegalStateException("The " + type.getSimpleName() + " cannot be created because "
						+ subitem.getClass().getSimpleName() + " with the same name exists");
			}
			return null;
		}
	}
	
	public void addReplaceExisting(Item item) {
		getValue().addReplaceExisting(item);
	}

	@Override
	public PrismObject<T> clone() {
		PrismObject<T> clone = new PrismObject<T>(getName(), getDefinition(), prismContext);
		copyValues(clone);
		return clone;
	}

	protected void copyValues(PrismObject<T> clone) {
		super.copyValues(clone);
		clone.oid = this.oid;
	}

	public ObjectDelta<T> compareTo(PrismObject<T> other) {
		if (other == null) {
			ObjectDelta<T> objectDelta = new ObjectDelta<T>(getCompileTimeClass(), ChangeType.DELETE);
			objectDelta.setOid(getOid());
			return objectDelta;
		}
		// This must be a modify
		ObjectDelta<T> objectDelta = new ObjectDelta<T>(getCompileTimeClass(), ChangeType.MODIFY);
		objectDelta.setOid(getOid());

		Collection<PropertyPath> thisPropertyPaths = listPropertyPaths();
		Collection<PropertyPath> otherPropertyPaths = other.listPropertyPaths();
		Collection<PropertyPath> allPropertyPaths = MiscUtil.union(thisPropertyPaths,otherPropertyPaths);

		for (PropertyPath path: allPropertyPaths) {
			PrismProperty thisProperty = findProperty(path);
			PrismProperty otherProperty = other.findProperty(path);
			PropertyDelta propertyDelta = null;

			if (thisProperty == null) {
				// this must be an add
				propertyDelta = new PropertyDelta(path, otherProperty.getDefinition());
				// TODO: mangle source
				propertyDelta.addValuesToAdd(otherProperty.getValues());
			} else {
				// TODO: mangle source
				propertyDelta = thisProperty.compareRealValuesTo(otherProperty);
			}
			if (propertyDelta != null && !propertyDelta.isEmpty()) {
				objectDelta.addModification(propertyDelta);
			}
		}

		return objectDelta;
	}

	private Collection<PropertyPath> listPropertyPaths() {
		List<PropertyPath> list = new ArrayList<PropertyPath>();
		addPropertyPathsToList(new PropertyPath(), list);
		return list;
	}

	/**
	 * Note: hashcode and equals compare the objects in the "java way". That means the objects must be
	 * almost preciselly equal to match (e.g. including source demarcation in values and other "annotations").
	 * For a method that compares the "meaningful" parts of the objects see equivalent().
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = super.hashCode();
		result = prime * result + ((oid == null) ? 0 : oid.hashCode());
		return result;
	}

	/**
	 * Note: hashcode and equals compare the objects in the "java way". That means the objects must be
	 * almost preciselly equal to match (e.g. including source demarcation in values and other "annotations").
	 * For a method that compares the "meaningful" parts of the objects see equivalent().
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!super.equals(obj))
			return false;
		if (getClass() != obj.getClass())
			return false;
		PrismObject other = (PrismObject) obj;
		if (oid == null) {
			if (other.oid != null)
				return false;
		} else if (!oid.equals(other.oid))
			return false;
		return true;
	}

	/**
	 * this method ignores some part of the object during comparison (e.g. source demarkation in values)
	 * These methods compare the "meaningful" parts of the objects.
	 */
	public boolean equivalent(Object obj) {
		// Alibistic implementation for now. But shoudl work well.
		if (this == obj)
			return true;
		if (getClass() != obj.getClass())
			return false;
		PrismObject other = (PrismObject) obj;
		if (oid == null) {
			if (other.oid != null)
				return false;
		} else if (!oid.equals(other.oid))
			return false;
		ObjectDelta<T> delta = compareTo(other);
		return delta.isEmpty();
	}

	/**
	 * Return a human readable name of this class suitable for logs.
	 */
	@Override
	protected String getDebugDumpClassName() {
		return "PO";
	}

	@Override
	protected String additionalDumpDescription() {
		return ", "+getOid();
	}

//	public Node serializeToDom() throws SchemaException {
//		Node doc = DOMUtil.getDocument();
//		serializeToDom(doc);
//		return doc;
//	}

}
