/*
 * Copyright (C) 2012-2016 Markus Junginger, greenrobot (http://greenrobot.org)
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
package org.greenrobot.eventbus;

import org.greenrobot.eventbus.meta.SubscriberInfo;
import org.greenrobot.eventbus.meta.SubscriberInfoIndex;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class SubscriberMethodFinder {
    /*
     * In newer class files, compilers may add methods. Those are called bridge or synthetic methods.
     * EventBus must ignore both. There modifiers are not public but defined in the Java class file format:
     * http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.6-200-A.1
     */
    private static final int BRIDGE = 0x40;
    private static final int SYNTHETIC = 0x1000;

    private static final int MODIFIERS_IGNORE = Modifier.ABSTRACT | Modifier.STATIC | BRIDGE | SYNTHETIC;

    /**
     * 缓存
     * */
    private static final Map<Class<?>, List<SubscriberMethod>> METHOD_CACHE = new ConcurrentHashMap<>();

    private List<SubscriberInfoIndex> subscriberInfoIndexes;
    private final boolean strictMethodVerification;

    /**
     * 是否要忽略生成的Index文件，也就是编译库生成的文件
     * */
    private final boolean ignoreGeneratedIndex;

    private static final int POOL_SIZE = 4;
    private static final FindState[] FIND_STATE_POOL = new FindState[POOL_SIZE];

    SubscriberMethodFinder(List<SubscriberInfoIndex> subscriberInfoIndexes, boolean strictMethodVerification,
                           boolean ignoreGeneratedIndex) {
        this.subscriberInfoIndexes = subscriberInfoIndexes;
        this.strictMethodVerification = strictMethodVerification;
        this.ignoreGeneratedIndex = ignoreGeneratedIndex;
    }

    /**
     * 找到某一个类中被注解的方法
     * */
    List<SubscriberMethod> findSubscriberMethods(Class<?> subscriberClass) {
        // 先从缓存里面取
        List<SubscriberMethod> subscriberMethods = METHOD_CACHE.get(subscriberClass);
        // 如果在缓存中找到了，直接返回
        if (subscriberMethods != null) {
            return subscriberMethods;
        }
        // 如果忽略生成的文件，通过反射得到被注解的方法
        if (ignoreGeneratedIndex) {
            subscriberMethods = findUsingReflection(subscriberClass);
        }
        // 直接查找被注解的方法
        else {
            subscriberMethods = findUsingInfo(subscriberClass);
        }
        if (subscriberMethods.isEmpty()) {
            throw new EventBusException("Subscriber " + subscriberClass
                    + " and its super classes have no public methods with the @Subscribe annotation");
        } else {
            // 把查找结果和对应的类放入到缓存中
            METHOD_CACHE.put(subscriberClass, subscriberMethods);
            return subscriberMethods;
        }
    }

    /**
     * 从编译生成的文件中查找被注解的方法
     * */
    private List<SubscriberMethod> findUsingInfo(Class<?> subscriberClass) {
        // 同样是得到一个查找类的对象
        FindState findState = prepareFindState();
        findState.initForSubscriber(subscriberClass);
        // 开始循环查找
        while (findState.clazz != null) {
            // 获取被注解的信息
            findState.subscriberInfo = getSubscriberInfo(findState);
            // 如果不为空
            if (findState.subscriberInfo != null) {
                // 通过遍历被注解的方法
                SubscriberMethod[] array = findState.subscriberInfo.getSubscriberMethods();
                for (SubscriberMethod subscriberMethod : array) {
                    // 检查房是否添加成功，
                    if (findState.checkAdd(subscriberMethod.method, subscriberMethod.eventType)) {
                        findState.subscriberMethods.add(subscriberMethod);
                    }
                }
            }
            // 通过反射再去获取一遍
            else {
                findUsingReflectionInSingleClass(findState);
            }
            // 再次查找父类
            findState.moveToSuperclass();
        }
        // 得到查找的结果
        return getMethodsAndRelease(findState);
    }

    /**
     * 得到查找的结果
     * */
    private List<SubscriberMethod> getMethodsAndRelease(FindState findState) {
        // 把刚才的查找结果放入新建的ArrayList中
        List<SubscriberMethod> subscriberMethods = new ArrayList<>(findState.subscriberMethods);
        // 回收查找对象
        findState.recycle();
        // 并且把查找对象放入到缓存中，等待下次利用
        synchronized (FIND_STATE_POOL) {
            for (int i = 0; i < POOL_SIZE; i++) {
                if (FIND_STATE_POOL[i] == null) {
                    FIND_STATE_POOL[i] = findState;
                    break;
                }
            }
        }
        // 返回查找的ArrayList
        return subscriberMethods;
    }

    /**
     * 返回一个查找类
     * */
    private FindState prepareFindState() {
        // 从FIND_STATE_POOL中找到第一个不为空的FindState返回
        synchronized (FIND_STATE_POOL) {
            for (int i = 0; i < POOL_SIZE; i++) {
                FindState state = FIND_STATE_POOL[i];
                if (state != null) {
                    FIND_STATE_POOL[i] = null;
                    return state;
                }
            }
        }
        // 如果全是空的，新建一个FindState返回
        return new FindState();
    }

    /**
     *
     * */
    private SubscriberInfo getSubscriberInfo(FindState findState) {
        // 如果查找类本身有注解相关的信息，因为是异步的，有可能这个类还是被回收
        if (findState.subscriberInfo != null && findState.subscriberInfo.getSuperSubscriberInfo() != null) {
            SubscriberInfo superclassInfo = findState.subscriberInfo.getSuperSubscriberInfo();
            // 判断现在要查找的class 和 之前查找的class是否相同，如果相同直接返回结果
            if (findState.clazz == superclassInfo.getSubscriberClass()) {
                return superclassInfo;
            }
        }
        // 这里可以通过Builder来设置subscriberInfoIndexes，添加我们生成的类
        // 然后就可以直接从生产的类中直接查找信息
        if (subscriberInfoIndexes != null) {
            for (SubscriberInfoIndex index : subscriberInfoIndexes) {
                SubscriberInfo info = index.getSubscriberInfo(findState.clazz);
                if (info != null) {
                    return info;
                }
            }
        }
        return null;
    }

    /**
     * 通过反射的到类的被注解的方法
     * */
    private List<SubscriberMethod> findUsingReflection(Class<?> subscriberClass) {
        // 得到一个查找类对象
        FindState findState = prepareFindState();
        // 设置要查找的类
        findState.initForSubscriber(subscriberClass);
        // 开始循环查找
        while (findState.clazz != null) {
            // 通过反射查找类中被注解的方法
            findUsingReflectionInSingleClass(findState);
            // 接着查找父类
            findState.moveToSuperclass();
        }
        // 得到刚才循环查找的结果
        return getMethodsAndRelease(findState);
    }

    /**
     * 通过反射，查找使用一个类中使用注解的方法
     * */
    private void findUsingReflectionInSingleClass(FindState findState) {
        Method[] methods;
        try {
            // This is faster than getMethods, especially when subscribers are fat classes like Activities
            // 得到所有的方法
            methods = findState.clazz.getDeclaredMethods();
        } catch (Throwable th) {
            // Workaround for java.lang.NoClassDefFoundError, see https://github.com/greenrobot/EventBus/issues/149
            // 得到所有的方法
            methods = findState.clazz.getMethods();
            findState.skipSuperClasses = true;
        }
        // 遍历这些方法
        for (Method method : methods) {
            // 得到这个方法的修饰符
            int modifiers = method.getModifiers();
            // 如果是public 并且忽略几种类的情况（抽象类，静态类等）
            if ((modifiers & Modifier.PUBLIC) != 0 && (modifiers & MODIFIERS_IGNORE) == 0) {
                // 得到房阿德参数类型数组
                Class<?>[] parameterTypes = method.getParameterTypes();
                // 参数的个数是1
                if (parameterTypes.length == 1) {
                    // 得到这个方法的Subscribe注解
                    Subscribe subscribeAnnotation = method.getAnnotation(Subscribe.class);
                    // 如果这个方法被Subscribe注解了
                    if (subscribeAnnotation != null) {
                        // 得到参数的class类型
                        Class<?> eventType = parameterTypes[0];
                        // 检查是否添加成功
                        if (findState.checkAdd(method, eventType)) {
                            // 获取注解的ThreadMode的值
                            ThreadMode threadMode = subscribeAnnotation.threadMode();
                            // 还记得注解库中的那段乱遭的代码吗，他们的作用是一样的，把类和对应的注解的方法，保存起来
                            // 请注意eventType，这个是把参数的类型作为eventType
                            findState.subscriberMethods.add(new SubscriberMethod(method, eventType, threadMode,
                                    subscribeAnnotation.priority(), subscribeAnnotation.sticky()));
                        }
                    }
                } else if (strictMethodVerification && method.isAnnotationPresent(Subscribe.class)) {
                    String methodName = method.getDeclaringClass().getName() + "." + method.getName();
                    throw new EventBusException("@Subscribe method " + methodName +
                            "must have exactly 1 parameter but has " + parameterTypes.length);
                }
            } else if (strictMethodVerification && method.isAnnotationPresent(Subscribe.class)) {
                String methodName = method.getDeclaringClass().getName() + "." + method.getName();
                throw new EventBusException(methodName +
                        " is a illegal @Subscribe method: must be public, non-static, and non-abstract");
            }
        }
    }

    static void clearCaches() {
        METHOD_CACHE.clear();
    }

    /**
     * 一个用来查找类的方法的类
     * */
    static class FindState {
        final List<SubscriberMethod> subscriberMethods = new ArrayList<>();
        final Map<Class, Object> anyMethodByEventType = new HashMap<>();
        final Map<String, Class> subscriberClassByMethodKey = new HashMap<>();
        final StringBuilder methodKeyBuilder = new StringBuilder(128);

        Class<?> subscriberClass;
        Class<?> clazz;
        boolean skipSuperClasses;
        SubscriberInfo subscriberInfo;

        void initForSubscriber(Class<?> subscriberClass) {
            this.subscriberClass = clazz = subscriberClass;
            skipSuperClasses = false;
            subscriberInfo = null;
        }

        void recycle() {
            subscriberMethods.clear();
            anyMethodByEventType.clear();
            subscriberClassByMethodKey.clear();
            methodKeyBuilder.setLength(0);
            subscriberClass = null;
            clazz = null;
            skipSuperClasses = false;
            subscriberInfo = null;
        }

        /**
         * 检查是否把方法添加成功
         * */
        boolean checkAdd(Method method, Class<?> eventType) {
            // 2 level check: 1st level with event type only (fast), 2nd level with complete signature when required.
            // Usually a subscriber doesn't have methods listening to the same event type.
            // 把参数类型和方法放入到集合中
            Object existing = anyMethodByEventType.put(eventType, method);
            // 如果eventType之前没有对应的value，会返回null
            if (existing == null) {
                return true;
            }
            //  // 如果eventType之前有对应的value，会返回之前的对象
            else {
                // 对之前的对象是一个方法
                if (existing instanceof Method) {
                    // 检查方法是否符合要求
                    if (!checkAddWithMethodSignature((Method) existing, eventType)) {
                        // Paranoia check
                        throw new IllegalStateException();
                    }
                    // Put any non-Method object to "consume" the existing Method
                    // 这个EventType对应自己
                    anyMethodByEventType.put(eventType, this);
                }
                // 这里对method也进行了一次检查
                return checkAddWithMethodSignature(method, eventType);
            }
        }

        /**
         * 检查添加方法的特征
         * */
        private boolean checkAddWithMethodSignature(Method method, Class<?> eventType) {
            methodKeyBuilder.setLength(0);
            methodKeyBuilder.append(method.getName());
            methodKeyBuilder.append('>').append(eventType.getName());
            // 创建这个method的对应的key
            String methodKey = methodKeyBuilder.toString();
            // 得到方法的声明的类
            Class<?> methodClass = method.getDeclaringClass();
            // 把key和类进行保存
            Class<?> methodClassOld = subscriberClassByMethodKey.put(methodKey, methodClass);
            // 如果之前并没有保存过 或者 methodClass和methodClassOld是相同的类，或者methodClass是methodClassOld的父类
            if (methodClassOld == null || methodClassOld.isAssignableFrom(methodClass)) {
                // Only add if not already found in a sub class
                return true;
            } else {
                // Revert the put, old class is further down the class hierarchy
                // 还是保存之前的值，相当于撤销了刚才的保存操作
                subscriberClassByMethodKey.put(methodKey, methodClassOld);
                return false;
            }
        }

        /**
         * 设置当前查找的类变为自己的父类，如果父类已经是Java 或者android的内置对象，查找的类设置为null
         * */
        void moveToSuperclass() {
            if (skipSuperClasses) {
                clazz = null;
            } else {
                clazz = clazz.getSuperclass();
                String clazzName = clazz.getName();
                /** Skip system classes, this just degrades performance. */
                if (clazzName.startsWith("java.") || clazzName.startsWith("javax.") || clazzName.startsWith("android.")) {
                    clazz = null;
                }
            }
        }
    }

}
