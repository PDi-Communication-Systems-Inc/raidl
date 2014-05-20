/*
 * Copyright (C) 2014 Opersys inc.
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

package com.opersys.raidl;

import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

public class Raidl {

    private static int listServices() {
        try {
            IBinder serviceBinder;
            String serviceInterface;

            for (String serviceName : ServiceManager.listServices()) {
                serviceBinder = ServiceManager.getService(serviceName);
                serviceInterface = serviceBinder.getInterfaceDescriptor();

                if (!serviceInterface.equals(""))
                    System.out.println(serviceName + ": " + serviceInterface);
                else
                    System.out.println(serviceName + ": No Interface");
            }

            return 0;

        } catch (RemoteException ex) {
            System.err.println("Error listing services");
            ex.printStackTrace(System.err);
            return 1;
        }
    }

    private static int showVersion() {
        System.out.println("Raidl: version 0.9.0");
        return 0;
    }

    private static String join(Collection<?> s, String delimiter) {
        StringBuilder builder = new StringBuilder();
        Iterator<?> iter = s.iterator();

        while (iter.hasNext()) {
            builder.append(iter.next());
            if (!iter.hasNext()) {
                break;
            }
            builder.append(delimiter);
        }

        return builder.toString();
    }

    private static String simplifyType(Class<?> clazz, String packageName, Set<String> classImports) {
        if (clazz.getCanonicalName().startsWith("java.lang") || clazz.getCanonicalName().startsWith(packageName))
            return clazz.getSimpleName();
        else {
            if (!(clazz.isPrimitive() || clazz.isArray()))
                classImports.add(clazz.getCanonicalName());

            return clazz.getSimpleName();
        }
    }

    private static boolean isRemoteMethod(Method method) {
        boolean isRemoteMethod = false;

        for (Class<?> methodException : method.getExceptionTypes()) {
            if (methodException == RemoteException.class) {
                isRemoteMethod = true;
                break;
            }
        }

        return isRemoteMethod;
    }

    private static Class tryloadServiceClass(String serviceClassName) throws ClassNotFoundException {
        Class serviceClass = null;
        int idx = 0;

        // This is a list of namespaces in which to search for service that might return a simplified
        // interface name instead of a canonical name.
        String[] androidNamespacesPrefixes = {
                "",
                "android.os.",
                "android.os.storage.",
                "android.service.",
                "android.service.notification.",
                "android.service.textservice.",
                "android.accessibilityservice"
        };

        while (serviceClass == null) {
            String augmentedInterfaceName;

            if (idx == androidNamespacesPrefixes.length)
                throw new ClassNotFoundException("Class not found for "
                        + serviceClassName
                        + " (C++ services not supported)");

            augmentedInterfaceName = androidNamespacesPrefixes[idx++] + serviceClassName;

            try {
                serviceClass = Raidl.class.getClassLoader().loadClass(augmentedInterfaceName);
            } catch (ClassNotFoundException ex) {
                serviceClass = null;
            }
        }

        return serviceClass;
    }

    private static boolean looksLikeTransactionCode(String transactionCodeName) {
        return transactionCodeName.startsWith("TRANSACTION_") || transactionCodeName.endsWith("_TRANSACTION");
    }

    private static String getMethodNameForTransaction(String serviceName, String transactionCodeName) {
        // This is a list of methods in IActivityManager for which the service code doesn't
        // quite give us the method name after our simple transformation.
        String[][] activityServiceQuirks = {
                { "clearAppData", "clearApplicationUserData" },
                { "getDeviceConfiguration", "getDeviceConfigurationInfo" },
                { "startBackupAgent", "bindBackupAgent" }
        };

        if (transactionCodeName.startsWith("TRANSACTION_")) {
            return transactionCodeName.replace("TRANSACTION_", "");
        }
        // This is to handle transaction codes in the style of IActivityManager.java
        else if (transactionCodeName.endsWith("_TRANSACTION")) {
            String[] transactMethNameParts = transactionCodeName.replace("_TRANSACTION", "").split("_");
            String transactMethName = "";

            for (String namePart : transactMethNameParts) {
                if (transactMethName.equals(""))
                    transactMethName += namePart.toLowerCase();
                else
                    transactMethName += namePart.substring(0, 1) + namePart.substring(1).toLowerCase();
            }

            if (serviceName.equals("activity")) {
                for (String[] quirk : activityServiceQuirks) {
                    if (quirk[0].equals(transactMethName))
                        return quirk[1];
                }
            }

            return transactMethName;
        }

        throw new IllegalArgumentException(
                "Codename doesn't look like a transaction code constant: " + transactionCodeName);
    }

    private static int reverseAidl(boolean showCodes, String serviceName, String desiredMethodName, Integer desiredMethodCode) {
        IBinder serviceBinder;
        String serviceClassName, packageName;
        Class<?> serviceClass = null, serviceStubClass;
        SortedMap<Integer, String> serviceCodesMethods;
        LinkedList<String> aidlMethods;
        Map<String, Method> serviceMethods;
        SortedSet<String> classImports;
        boolean singleDisplay = false;

        // Determine if we output a full AIDL or just the signature of one method.

        if (desiredMethodName != null || desiredMethodCode != null)
            singleDisplay = true;

        serviceBinder = ServiceManager.getService(serviceName);

        if (serviceBinder == null) {
            System.err.println("Unable to get service: " + serviceName);
            return 1;
        }

        try {
            serviceClassName = serviceBinder.getInterfaceDescriptor();

            if (serviceClassName.equals("")) {
                System.err.println("No interface descriptor returned for service: '" + serviceName + "'");
                return 1;
            }

            serviceClass = tryloadServiceClass(serviceClassName);

            if (!serviceName.equals("activity"))
                serviceStubClass = Raidl.class.getClassLoader().loadClass(serviceClass.getCanonicalName() + "$Stub");
            else
                serviceStubClass = serviceClass;

            packageName = serviceClass.getCanonicalName().substring(0, serviceClass.getCanonicalName().lastIndexOf("."));

            serviceCodesMethods = new TreeMap<Integer, String>();

            // Get the transaction codes.
            for (Field serviceField : serviceStubClass.getDeclaredFields()) {
                int serviceFieldValue;
                String methodName;

                if (serviceField.getType() == int.class && looksLikeTransactionCode(serviceField.getName())) {
                    serviceField.setAccessible(true);
                    serviceFieldValue = serviceField.getInt(null);
                    methodName = getMethodNameForTransaction(serviceName, serviceField.getName());
                    serviceCodesMethods.put(serviceFieldValue, methodName);
                }
            }

            serviceMethods = new HashMap<String, Method>();

            // Get the methods by name.
            for (Method serviceMethod : serviceClass.getMethods())
                serviceMethods.put(serviceMethod.getName(), serviceMethod);

            aidlMethods = new LinkedList<String>();
            classImports = new TreeSet<String>();

            for (Integer serviceCode : serviceCodesMethods.keySet()) {
                Class<?> methodReturnType;
                Class<?>[] methodParams;
                Method serviceMethod;
                List<String> methodParamTypes;
                String methodParamString, serviceCodeMethodName;
                int paramNo = 1;

                serviceCodeMethodName = serviceCodesMethods.get(serviceCode);

                // Examine just what the user passed as command line argument.

                if (desiredMethodCode != null && !serviceCode.equals(desiredMethodCode))
                    continue;

                if (desiredMethodName != null && !desiredMethodName.equals(serviceCodeMethodName))
                    continue;

                serviceMethod = serviceMethods.get(serviceCodeMethodName);
                methodParamTypes = new LinkedList<String>();

                if (serviceMethod == null)
                    throw new NullPointerException("Could not find method: " + serviceCodeMethodName);

                if (isRemoteMethod(serviceMethod)) {
                    methodReturnType = serviceMethod.getReturnType();
                    methodParams = serviceMethod.getParameterTypes();

                    for (Class<?> methodParamType : methodParams) {
                        String methodParamName;

                        if (methodParamType == int.class || methodParamType == long.class)
                            methodParamName = "n" + paramNo++;
                        else if (methodParamType == String.class)
                            methodParamName = "s" + paramNo++;
                        else
                            methodParamName = "p" + paramNo++;

                        methodParamTypes.add(
                                simplifyType(methodParamType, packageName, classImports) + " " + methodParamName);
                    }

                    methodParamString = join(methodParamTypes, ", ");

                    aidlMethods.add(simplifyType(methodReturnType, packageName, classImports)
                            + " "
                            + serviceMethod.getName()
                            + "("
                            + methodParamString
                            + ") throws RemoteException;"
                            + (showCodes ? " // " + serviceCode : "")
                    );
                }
            }

            // Displaying starts here.

            if (!singleDisplay) {
                System.out.println("// Service: " + serviceName + ", Interface: " + serviceClassName);
                System.out.println("package " + packageName + ";\n");

                if (classImports.size() > 0) {
                    for (String classImport : classImports)
                        System.out.println("import " + classImport + ";");

                    System.out.println();
                }

                System.out.println("interface " + serviceClass.getSimpleName() + " {") ;
            }

            System.out.println((!singleDisplay ? "    " : "")
                    + join(aidlMethods, "\n\n"
                    + (!singleDisplay ? "    " : "")));

            if (!singleDisplay)
                System.out.println("}");

        } catch (RemoteException e) {
            e.printStackTrace();

        } catch (ClassNotFoundException e) {
            System.err.println("Failed to load class for service '" + serviceName + "'");
            e.printStackTrace(System.err);

        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }

        return 0;
    }

    public static void main(String[] args) {
        CommandLine cmdLine;
        String serviceName, methodName = null;
        Integer methodCode = null;

        try {
            cmdLine = new CommandLine(args, "-n", "-v", "-l");

            if (cmdLine.hasOption("-v"))
                System.exit(showVersion());

            if (cmdLine.hasOption("-l"))
                System.exit(listServices());

            if (cmdLine.getArgs().length < 1)
                System.exit(showVersion());

            serviceName = cmdLine.getArgs()[0];

            if (cmdLine.getArgs().length == 2) {
                try {
                    methodCode = Integer.parseInt(cmdLine.getArgs()[1]);
                } catch (NumberFormatException ignored) {
                    methodName = cmdLine.getArgs()[1];
                }
            }

            System.exit(reverseAidl(cmdLine.hasOption("-n"), serviceName, methodName, methodCode));

        } catch (CommandLineException e) {
            System.err.println(e.getMessage());
        }

        System.exit(0);
    }
}
