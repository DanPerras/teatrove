/*
 *  Copyright 1997-2011 teatrove.org
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.teatrove.tea.util;

import java.beans.*;
import java.util.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.teatrove.trove.util.IdentityMap;

/**
 * The JavaBean Introspector for Tea.
 *
 * @author Brian S O'Neill
 * @version

 *
 * @see java.beans.Introspector
 */
public class BeanAnalyzer {
    /** The name given to the length property: "length" */
    public static final String LENGTH_PROPERTY_NAME = "length";

    /** The name given to keyed properties: "[]" */
    public static final String KEYED_PROPERTY_NAME = "[]";

    /** 
     * The name of the special field that specializes a 
     * keyed property type: "ELEMENT_TYPE"
     */
    public static final String ELEMENT_TYPE_FIELD_NAME = "ELEMENT_TYPE";

    /** A cache of properties for classes. Maps classes to property maps. */
    private static Map cPropertiesCache;

    static {
        Introspector.setBeanInfoSearchPath(new String[0]);
        cPropertiesCache = new IdentityMap();
    }

    /**
     * Test program.
     */
    public static void main(String[] args) throws Exception {
        Map map = getAllProperties(Class.forName(args[0]));
        Iterator keys = map.keySet().iterator();
        while (keys.hasNext()) {
            String key = (String)keys.next();
            PropertyDescriptor desc = (PropertyDescriptor)map.get(key);
            System.out.println(key + " = " + desc);
        }
    }

    /**
     * A function that returns a Map of all the available properties on
     * a given class including write-only properties. The properties returned
     * is mostly a superset of those returned from the standard JavaBeans 
     * Introspector except pure indexed properties are discarded.
     *
     * <p>Interfaces receive all the properties available in Object. Arrays, 
     * Strings and Collections all receive a "length" property. An array's 
     * "length" PropertyDescriptor has no read or write methods.
     *
     * <p>Instead of indexed properties, there may be keyed properties in the
     * map, represented by a {@link KeyedPropertyDescriptor}. Arrays, Strings
     * and Lists always have keyed properties with a key type of int.
     *
     * <p>Because the value returned from a keyed property method may be more
     * specific than the method signature describes (such is often the case
     * with collections), a bean class can contain a special field that
     * indicates what that specific type should be. The signature of this field
     * is as follows: 
     * <tt>public&nbsp;static&nbsp;final&nbsp;Class&nbsp;ELEMENT_TYPE&nbsp;=&nbsp;&lt;type&gt;.class;</tt>.
     * 
     * @return an unmodifiable mapping of property names (Strings) to
     * PropertyDescriptor objects.
     *
     */
    public static Map getAllProperties(Class clazz)
        throws IntrospectionException {
        
        Map properties = (Map)cPropertiesCache.get(clazz);
        if (properties == null) {
            properties = Collections.unmodifiableMap(createProperties(clazz));
            cPropertiesCache.put(clazz, properties);
        }

        return properties;
    }

    private static Map createProperties(Class clazz)
        throws IntrospectionException {

        Map properties = new HashMap();

        if (clazz == null || clazz.isPrimitive()) {
            return properties;
        }
        
        BeanInfo info;
        try {
            info = Introspector.getBeanInfo(clazz);
        }
        catch (LinkageError e) {
            throw new IntrospectionException(e.toString());
        }

        if (info != null) {
            PropertyDescriptor[] pdArray = info.getPropertyDescriptors();
            
            // Standard properties.
            int length = pdArray.length;
            for (int i=0; i<length; i++) {
                PropertyDescriptor desc = pdArray[i];
                // This check will discard standard pure indexed properties.
                if (desc.getPropertyType() != null) {
                    properties.put(desc.getName(), desc);
                }
            }
        }

        // Properties defined in Object are also available to interfaces.
        if (clazz.isInterface()) {
            properties.putAll(getAllProperties(Object.class));
        }

        // Ensure that all implemented interfaces are properly analyzed.
        Class[] interfaces = clazz.getInterfaces();
        for (int i=0; i<interfaces.length; i++) {
            properties.putAll(getAllProperties(interfaces[i]));
        }

        // All arrays have a "length" property and a keyed property.
        PropertyDescriptor property;
        if (clazz.isArray()) {
            property = new ArrayLengthProperty(clazz);
            properties.put(LENGTH_PROPERTY_NAME, property);
        }

        // Strings also have a "length" property.
        if (String.class.isAssignableFrom(clazz)) {
            try {
                Method readMethod = String.class.getMethod("length", null);
                property = new PropertyDescriptor
                    (LENGTH_PROPERTY_NAME, readMethod, null);
                properties.put(LENGTH_PROPERTY_NAME, property);
            }
            catch (NoSuchMethodException e) {
                throw new LinkageError(e.toString());
            }
        }

        // Collections also have a "length" property.
        if (Collection.class.isAssignableFrom(clazz)) {
            try {
                Method readMethod = Collection.class.getMethod("size", null);
                property = new PropertyDescriptor
                    (LENGTH_PROPERTY_NAME, readMethod, null);
                properties.put(LENGTH_PROPERTY_NAME, property);
            }
            catch (NoSuchMethodException e) {
                throw new LinkageError(e.toString());
            }
        }
        
        // Analyze design patterns for keyed properties.

        KeyedPropertyDescriptor keyed = new KeyedPropertyDescriptor();
        List keyedMethods = new ArrayList();

        if (clazz.isArray()) {
            keyed.setKeyedPropertyType(clazz.getComponentType());
            keyedMethods.add(null);
        }

        // Get index types and access methods.

        // Extract all the public "get" methods that have a return type and 
        // one parameter.
        
        Method[] methods = clazz.getMethods();
        
        for (int i=0; i<methods.length; i++) {
            Method m = methods[i];
            if (Modifier.isPublic(m.getModifiers()) &&
                "get".equals(m.getName())) {

                Class ret = m.getReturnType();
                if (ret != null && ret != void.class) {
                    Class[] params = m.getParameterTypes();
                    if (params.length == 1) {
                        // Found a method that fits the requirements.
                        keyed.setKeyedPropertyType(ret);
                        keyedMethods.add(m);
                    }
                }
            }
        }
        
        if (keyedMethods.size() == 0) {
            // If no "get" methods found, but this type is a Vector or
            // String, use elementAt or charAt as substitutes.
            
            if (Vector.class.isAssignableFrom(clazz)) {
                keyed.setKeyedPropertyType(Object.class);
                try {
                    Method m = Vector.class.getMethod
                        ("elementAt", new Class[] {int.class});
                    keyedMethods.add(m);
                }
                catch (NoSuchMethodException e) {
                    throw new LinkageError(e.toString());
                }
            }
            else if (String.class.isAssignableFrom(clazz)) {
                keyed.setKeyedPropertyType(char.class);
                try {
                    Method m = String.class.getMethod
                        ("charAt", new Class[] {int.class});
                    keyedMethods.add(m);
                }
                catch (NoSuchMethodException e) {
                    throw new LinkageError(e.toString());
                }
            }
        }

        if (keyedMethods.size() > 0) {
            // Try to specialize keyed property type.
            try {
                Field field = clazz.getField(ELEMENT_TYPE_FIELD_NAME);
                if (field.getType() == Class.class &&
                    Modifier.isStatic(field.getModifiers())) {
                    
                    Class elementType = (Class)field.get(null);
                    if (keyed.getKeyedPropertyType()
                        .isAssignableFrom(elementType)) {
                        
                        keyed.setKeyedPropertyType(elementType);
                    }
                }
            }
            catch (NoSuchFieldException e) {
            }
            catch (IllegalAccessException e) {
            }
        
            properties.put(KEYED_PROPERTY_NAME, keyed);
            int size = keyedMethods.size();
            keyed.setKeyedReadMethods
                ((Method[])keyedMethods.toArray(new Method[size]));
        }

        // Filter out properties with names that contain '$' characters.
        Iterator it = properties.keySet().iterator();
        while (it.hasNext()) {
            String propertyName = (String)it.next();
            if (propertyName.indexOf('$') >= 0) {
                it.remove();
            }
        }

        return properties;
    }

    private static class ArrayLengthProperty extends PropertyDescriptor {
        public ArrayLengthProperty(Class clazz) throws IntrospectionException {
            super(LENGTH_PROPERTY_NAME, clazz, null, null);
        }
        
        public Class getPropertyType() {
            return int.class;
        }
    }
}