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
package org.greenrobot.eventbus.annotationprocessor;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import de.greenrobot.common.ListMap;

@SupportedAnnotationTypes("org.greenrobot.eventbus.Subscribe")
@SupportedOptions(value = {"eventBusIndex", "verbose"})
public class EventBusAnnotationProcessor extends AbstractProcessor {
    public static final String OPTION_EVENT_BUS_INDEX = "eventBusIndex";
    public static final String OPTION_VERBOSE = "verbose";

    /** Found subscriber methods for a class (without superclasses). */
    private final ListMap<TypeElement, ExecutableElement> methodsByClass = new ListMap<>();
    private final Set<TypeElement> classesToSkip = new HashSet<>();

    private boolean writerRoundDone;
    private int round;
    private boolean verbose;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
        Messager messager = processingEnv.getMessager();
        try {
            // 通过命令行得到了EventBus的索引值，这个值通过apt工具类配置，用来找到程序的包名，也是文件要生成的位置
            String index = processingEnv.getOptions().get(OPTION_EVENT_BUS_INDEX);
            // 检查是否配置了参数
            if (index == null) {
                messager.printMessage(Diagnostic.Kind.ERROR, "No option " + OPTION_EVENT_BUS_INDEX +
                        " passed to annotation processor");
                return false;
            }
            //
            verbose = Boolean.parseBoolean(processingEnv.getOptions().get(OPTION_VERBOSE));
            // 此处通过字符串的截取得到了包名
            int lastPeriod = index.lastIndexOf('.');
            String indexPackage = lastPeriod != -1 ? index.substring(0, lastPeriod) : null;

            round++;
            // 接下来进行了一些检查，这个就不用看了
            if (verbose) {
                messager.printMessage(Diagnostic.Kind.NOTE, "Processing round " + round + ", new annotations: " +
                        !annotations.isEmpty() + ", processingOver: " + env.processingOver());
            }
            if (env.processingOver()) {
                if (!annotations.isEmpty()) {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                            "Unexpected processing state: annotations still available after processing over");
                    return false;
                }
            }
            if (annotations.isEmpty()) {
                return false;
            }

            if (writerRoundDone) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "Unexpected processing state: annotations still available after writing.");
            }
            // 这里才是重点，开始收集Subscriber注解
            collectSubscribers(annotations, env, messager);
            // 开始检查某些注解是否要忽略
            checkForSubscribersToSkip(messager, indexPackage);
            // 检查是否被注解的方法的集合 是空的
            if (!methodsByClass.isEmpty()) {
                // 开始创建文件
                createInfoIndexFile(index);
            } else {
                messager.printMessage(Diagnostic.Kind.WARNING, "No @Subscribe annotations found");
            }
            writerRoundDone = true;
        } catch (RuntimeException e) {
            // IntelliJ does not handle exceptions nicely, so log and print a message
            e.printStackTrace();
            messager.printMessage(Diagnostic.Kind.ERROR, "Unexpected error in EventBusAnnotationProcessor: " + e);
        }
        return true;
    }

    /**
     * 收集所有的注解
     * */
    private void collectSubscribers(Set<? extends TypeElement> annotations, RoundEnvironment env, Messager messager) {
        // 遍历所有的注解
        for (TypeElement annotation : annotations) {
            // 获取使用注解的所有元素
            Set<? extends Element> elements = env.getElementsAnnotatedWith(annotation);
            // 遍历所有元素
            for (Element element : elements) {
                // 判断这个被注解的元素是否是一个方法
                if (element instanceof ExecutableElement) {
                    ExecutableElement method = (ExecutableElement) element;
                    // 对这个方法进行必要的检查，不允许static/ 必须是public / 只能有一个参数
                    if (checkHasNoErrors(method, messager)) {
                        // 找到这个方法的类
                        TypeElement classElement = (TypeElement) method.getEnclosingElement();
                        // 把类个方法保存起来
                        methodsByClass.putElement(classElement, method);
                    }
                } else {
                    messager.printMessage(Diagnostic.Kind.ERROR, "@Subscribe is only valid for methods", element);
                }
            }
        }
    }

    /**
     * 对方法进行检查
     * */
    private boolean checkHasNoErrors(ExecutableElement element, Messager messager) {
        // 不允许是static静态方法
        if (element.getModifiers().contains(Modifier.STATIC)) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Subscriber method must not be static", element);
            return false;
        }
        // 只能是public
        if (!element.getModifiers().contains(Modifier.PUBLIC)) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Subscriber method must be public", element);
            return false;
        }

        // 只能含有一个方法参数
        List<? extends VariableElement> parameters = ((ExecutableElement) element).getParameters();
        if (parameters.size() != 1) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Subscriber method must have exactly 1 parameter", element);
            return false;
        }
        return true;
    }

    /**
     * Subscriber classes should be skipped if their class or any involved event class are not visible to the index.
     */
    private void checkForSubscribersToSkip(Messager messager, String myPackage) {
        // 遍历刚才获得类和其对应的注解的集合
        for (TypeElement skipCandidate : methodsByClass.keySet()) {
            // 得到类
            TypeElement subscriberClass = skipCandidate;
            // 开始循环判断判断，一直找到最顶端的父类
            while (subscriberClass != null) {
                // 检查类是否可见
                if (!isVisible(myPackage, subscriberClass)) {
                    // 如果类是不可见的，把他保存到不可见类的集合中
                    boolean added = classesToSkip.add(skipCandidate);
                    // 打印出错误日志
                    if (added) {
                        String msg;
                        if (subscriberClass.equals(skipCandidate)) {
                            msg = "Falling back to reflection because class is not public";
                        } else {
                            msg = "Falling back to reflection because " + skipCandidate +
                                    " has a non-public super class";
                        }
                        messager.printMessage(Diagnostic.Kind.NOTE, msg, subscriberClass);
                    }
                    break;
                }
                // 获取这个类中被注解的方法
                List<ExecutableElement> methods = methodsByClass.get(subscriberClass);
                // 判空
                if (methods != null) {
                    // 遍历方法
                    for (ExecutableElement method : methods) {
                        String skipReason = null;
                        // 得到第一个参数
                        VariableElement param = method.getParameters().get(0);
                        // 得到参数的类型
                        TypeMirror typeMirror = getParamTypeMirror(param, messager);
                        // 如果参数不是类或者是接口，不会处理
                        if (!(typeMirror instanceof DeclaredType) ||
                                !(((DeclaredType) typeMirror).asElement() instanceof TypeElement)) {
                            skipReason = "event type cannot be processed";
                        }
                        if (skipReason == null) {
                            // 获取这个元素的类名
                            TypeElement eventTypeElement = (TypeElement) ((DeclaredType) typeMirror).asElement();
                            // 判断类名是否可见，否则也不处理
                            if (!isVisible(myPackage, eventTypeElement)) {
                                skipReason = "event type is not public";
                            }
                        }
                        // 如果经过上面的检查，这个注解要被忽略
                        if (skipReason != null) {
                            // 添加到被忽略的结合中，并且出书错误日志
                            boolean added = classesToSkip.add(skipCandidate);
                            if (added) {
                                String msg = "Falling back to reflection because " + skipReason;
                                if (!subscriberClass.equals(skipCandidate)) {
                                    msg += " (found in super class for " + skipCandidate + ")";
                                }
                                messager.printMessage(Diagnostic.Kind.NOTE, msg, param);
                            }
                            break;
                        }
                    }
                }
                // 找到自己的父类，再次循环
                subscriberClass = getSuperclass(subscriberClass);
            }
        }
    }

    /**
     *  获取指定元素的类型
     * */
    private TypeMirror getParamTypeMirror(VariableElement param, Messager messager) {
        // 得到参数的类型
        TypeMirror typeMirror = param.asType();
        // Check for generic type
        // 检查这个类型是否被定义了，详细可以看TypeVariable的定义
        // TypeVariable 有类型 或者有方法 或者有构造方法
        if (typeMirror instanceof TypeVariable) {
            // 得到这个类型的父类
            TypeMirror upperBound = ((TypeVariable) typeMirror).getUpperBound();
            // 如果他的父类是一个类或者是接口
            if (upperBound instanceof DeclaredType) {
                if (messager != null) {
                    messager.printMessage(Diagnostic.Kind.NOTE, "Using upper bound type " + upperBound +
                            " for generic parameter", param);
                }
                // 返回父类的类型
                typeMirror = upperBound;
            }
        }
        return typeMirror;
    }

    /**
     * 获取一个元素的父类
     * */
    private TypeElement getSuperclass(TypeElement type) {
        // 如果父类是一个类或者是一个接口
        if (type.getSuperclass().getKind() == TypeKind.DECLARED) {
            // 得到父类元素
            TypeElement superclass = (TypeElement) processingEnv.getTypeUtils().asElement(type.getSuperclass());
            // 获取父类的类名
            String name = superclass.getQualifiedName().toString();
            // 如果父类已经是java类 或者是 android类 ，说明已经到父类的顶端了，所以返回null，停止循环
            if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("android.")) {
                // Skip system classes, this just degrades performance
                return null;
            }
            // 返回父类
            else {
                return superclass;
            }
        }
        // 如果父类已经不是类或者是接口，直接返回null，停止循环
        else {
            return null;
        }
    }

    /**
     * 获取完整的类名
     * */
    private String getClassString(TypeElement typeElement, String myPackage) {
        // 找到这个元素的最外层元素
        PackageElement packageElement = getPackageElement(typeElement);
        // 得到包名
        String packageString = packageElement.getQualifiedName().toString();
        // 得到类名
        String className = typeElement.getQualifiedName().toString();
        // 如果包名不为空
        if (packageString != null && !packageString.isEmpty()) {
            // 如果包名和配置的包名是一样的，只单独截取包名
            if (packageString.equals(myPackage)) {
                className = cutPackage(myPackage, className);
            }
            // 如果报名时Java包，返回类名
            else if (packageString.equals("java.lang")) {
                className = typeElement.getSimpleName().toString();
            }
        }
        return className;
    }

    /**
     * 截取包名
     * */
    private String cutPackage(String paket, String className) {
        // 如果类名正好是在这个包名中，单独把类名截取出来
        if (className.startsWith(paket + '.')) {
            // Don't use TypeElement.getSimpleName, it doesn't work for us with inner classes
            return className.substring(paket.length() + 1);
        } else {
            // Paranoia
            throw new IllegalStateException("Mismatching " + paket + " vs. " + className);
        }
    }

    /**
     * 获取具有包名的元素
     * */
    private PackageElement getPackageElement(TypeElement subscriberClass) {
        // 得到外部类
        Element candidate = subscriberClass.getEnclosingElement();
        // 一直找到最外层的类
        while (!(candidate instanceof PackageElement)) {
            candidate = candidate.getEnclosingElement();
        }
        return (PackageElement) candidate;
    }

    /**
     * 写入被注解的方法
     * */
    private void writeCreateSubscriberMethods(BufferedWriter writer, List<ExecutableElement> methods,
                                              String callPrefix, String myPackage) throws IOException {
        // 开始遍历方法
        for (ExecutableElement method : methods) {
            // 得到放的参数
            List<? extends VariableElement> parameters = method.getParameters();
            // 得到第一个参数的类型
            TypeMirror paramType = getParamTypeMirror(parameters.get(0), null);
            // 得到这个类型的元素
            TypeElement paramElement = (TypeElement) processingEnv.getTypeUtils().asElement(paramType);
            // 得到方法名
            String methodName = method.getSimpleName().toString();
            // 得到类名，并且拼接了.class
            String eventClass = getClassString(paramElement, myPackage) + ".class";
            //  得到注解对象
            Subscribe subscribe = method.getAnnotation(Subscribe.class);
            List<String> parts = new ArrayList<>();
            // 开始把字符放入到list中
            // 这是方法名
            parts.add(callPrefix + "(\"" + methodName + "\",");
            String lineEnd = "),";
            // 判断优先级
            if (subscribe.priority() == 0 && !subscribe.sticky()) {
                // 加入类名
                if (subscribe.threadMode() == ThreadMode.POSTING) {
                    parts.add(eventClass + lineEnd);
                } else {
                    // 加入类名
                    parts.add(eventClass + ",");
                    // 加入线程名
                    parts.add("ThreadMode." + subscribe.threadMode().name() + lineEnd);
                }
            } else {
                // 加入类名
                parts.add(eventClass + ",");
                // 加入线程名
                parts.add("ThreadMode." + subscribe.threadMode().name() + ",");
                parts.add(subscribe.priority() + ",");
                parts.add(subscribe.sticky() + lineEnd);
            }
            writeLine(writer, 3, parts.toArray(new String[parts.size()]));

            if (verbose) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Indexed @Subscribe at " +
                        method.getEnclosingElement().getSimpleName() + "." + methodName +
                        "(" + paramElement.getSimpleName() + ")");
            }

        }
    }

    /**
     * 开始创建每个类的索引文件，也是生成的Java文件
     *
     *   @param index 文件生成的位置
     * */
    private void createInfoIndexFile(String index) {
        BufferedWriter writer = null;
        try {
            // 在指定的位置，创建一个Java源文件
            JavaFileObject sourceFile = processingEnv.getFiler().createSourceFile(index);
            int period = index.lastIndexOf('.');
            // 截取包名
            String myPackage = period > 0 ? index.substring(0, period) : null;
            // 截取类名
            String clazz = index.substring(period + 1);
            // 创建字符输出流
            writer = new BufferedWriter(sourceFile.openWriter());
            // 写入包名
            if (myPackage != null) {
                writer.write("package " + myPackage + ";\n\n");
            }
            // 写入要引入的包和类
            writer.write("import org.greenrobot.eventbus.meta.SimpleSubscriberInfo;\n");
            writer.write("import org.greenrobot.eventbus.meta.SubscriberMethodInfo;\n");
            writer.write("import org.greenrobot.eventbus.meta.SubscriberInfo;\n");
            writer.write("import org.greenrobot.eventbus.meta.SubscriberInfoIndex;\n\n");
            writer.write("import org.greenrobot.eventbus.ThreadMode;\n\n");
            writer.write("import java.util.HashMap;\n");
            writer.write("import java.util.Map;\n\n");
            writer.write("/** This class is generated by EventBus, do not edit. */\n");
            // 这里开始定义类
            writer.write("public class " + clazz + " implements SubscriberInfoIndex {\n");
            // 创建一个私有的不可变的静态变量SUBSCRIBER_INDEX，类型是Map<Class<?>, SubscriberInfo>
            writer.write("    private static final Map<Class<?>, SubscriberInfo> SUBSCRIBER_INDEX;\n\n");
            // static 方法块，用来初始化静态变量
            writer.write("    static {\n");
            writer.write("        SUBSCRIBER_INDEX = new HashMap<Class<?>, SubscriberInfo>();\n\n");

            // 这里把所有的类和方法都写到文件里了
            writeIndexLines(writer, myPackage);
            writer.write("    }\n\n");
            // 定义putIndex方法，刚才的writeIndexLines中就是使用了这个方法
            // 把我们的类名，还有注解的方法名，还有定义的优先级和线程信息都放入了集合中
            writer.write("    private static void putIndex(SubscriberInfo info) {\n");
            writer.write("        SUBSCRIBER_INDEX.put(info.getSubscriberClass(), info);\n");
            writer.write("    }\n\n");
            writer.write("    @Override\n");
            writer.write("    public SubscriberInfo getSubscriberInfo(Class<?> subscriberClass) {\n");
            writer.write("        SubscriberInfo info = SUBSCRIBER_INDEX.get(subscriberClass);\n");
            writer.write("        if (info != null) {\n");
            writer.write("            return info;\n");
            writer.write("        } else {\n");
            writer.write("            return null;\n");
            writer.write("        }\n");
            writer.write("    }\n");
            writer.write("}\n");
        } catch (IOException e) {
            throw new RuntimeException("Could not write source for " + index, e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    //Silent
                }
            }
        }
    }

    /**
     *
     * */
    private void writeIndexLines(BufferedWriter writer, String myPackage) throws IOException {
        // 开始遍历被注解的方法集合
        for (TypeElement subscriberTypeElement : methodsByClass.keySet()) {
            // 这里做了一个检查，查看是否这个方法被忽略了
            if (classesToSkip.contains(subscriberTypeElement)) {
                continue;
            }
            // 得到类名
            String subscriberClass = getClassString(subscriberTypeElement, myPackage);
            // 检查这个类是否可见
            if (isVisible(myPackage, subscriberTypeElement)) {
                // 把类和被注解的方法都放入集合里面去
                writeLine(writer, 2,
                        "putIndex(new SimpleSubscriberInfo(" + subscriberClass + ".class,",
                        "true,", "new SubscriberMethodInfo[] {");
                List<ExecutableElement> methods = methodsByClass.get(subscriberTypeElement);
                writeCreateSubscriberMethods(writer, methods, "new SubscriberMethodInfo", myPackage);
                writer.write("        }));\n\n");
            } else {
                writer.write("        // Subscriber not visible to index: " + subscriberClass + "\n");
            }
        }
    }

    /**
     * 判断一个类是否可见
     * */
    private boolean isVisible(String myPackage, TypeElement typeElement) {
        // 获取类的修饰符
        Set<Modifier> modifiers = typeElement.getModifiers();
        boolean visible;
        // 如果这个类是public的，返回true
        if (modifiers.contains(Modifier.PUBLIC)) {
            visible = true;
        }
        // 如果这个类是PRIVATE 或 PROTECTED 返回false
        else if (modifiers.contains(Modifier.PRIVATE) || modifiers.contains(Modifier.PROTECTED)) {
            visible = false;
        }
        // 其他情况
        else {
            // 获取完整的包名
            String subscriberPackage = getPackageElement(typeElement).getQualifiedName().toString();
            // 如果包名是空的，说明类是在最外层，是可见的
            if (myPackage == null) {
                visible = subscriberPackage.length() == 0;
            } else {
                // 判断包名是否和类的包名相同，同样是可见的
                visible = myPackage.equals(subscriberPackage);
            }
        }
        return visible;
    }

    private void writeLine(BufferedWriter writer, int indentLevel, String... parts) throws IOException {
        writeLine(writer, indentLevel, 2, parts);
    }

    /**
     *
     * */
    private void writeLine(BufferedWriter writer, int indentLevel, int indentLevelIncrease, String... parts)
            throws IOException {
        writeIndent(writer, indentLevel);
        int len = indentLevel * 4;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i != 0) {
                if (len + part.length() > 118) {
                    writer.write("\n");
                    if (indentLevel < 12) {
                        indentLevel += indentLevelIncrease;
                    }
                    writeIndent(writer, indentLevel);
                    len = indentLevel * 4;
                } else {
                    writer.write(" ");
                }
            }
            writer.write(part);
            len += part.length();
        }
        writer.write("\n");
    }

    private void writeIndent(BufferedWriter writer, int indentLevel) throws IOException {
        for (int i = 0; i < indentLevel; i++) {
            writer.write("    ");
        }
    }
}
