package com.xtremelabs.robolectric.bytecode;


import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import static org.objectweb.asm.Type.getType;

public class AsmInstrumentingClassLoader extends ClassLoader implements Opcodes, InstrumentingClassLoader {
    private static final String OBJECT_DESC = Type.getDescriptor(Object.class);
    private static final Type OBJECT_TYPE = getType(Object.class);
    private static final Type STRING_TYPE = getType(String.class);
    private static final Type ROBOLECTRIC_INTERNALS_TYPE = Type.getType(RobolectricInternals.class);

    private static boolean debug = false;

    private final Setup setup;
    private final Map<String, Class> classes = new HashMap<String, Class>();

    public AsmInstrumentingClassLoader(Setup setup, ClassLoader classLoader) {
        super(classLoader);
        this.setup = setup;
    }

    @Override
    synchronized public Class loadClass(String name) throws ClassNotFoundException {
        System.out.println("loadClass " + name);
        Class<?> theClass = classes.get(name);
        if (theClass != null) return theClass;

        boolean shouldComeFromThisClassLoader = setup.shouldAcquire(name);

        if (shouldComeFromThisClassLoader) {
            theClass = findClass(name);
        } else {
            theClass = super.loadClass(name);
        }

        classes.put(name, theClass);
        return theClass;
    }

    @Override
    protected Class<?> findClass(final String className) throws ClassNotFoundException {
        if (setup.shouldAcquire(className)) {
            InputStream classBytesStream = getResourceAsStream(className.replace('.', '/') + ".class");

            if (classBytesStream == null) throw new ClassNotFoundException(className);

            Class<?> originalClass = super.loadClass(className);
            try {
                if (setup.shouldInstrument(originalClass)) {
                    byte[] bytes = getInstrumentedBytes(className, classBytesStream);
                    return defineClass(className, bytes, 0, bytes.length);
                } else {
                    byte[] bytes = readBytes(classBytesStream);
                    return defineClass(className, bytes, 0, bytes.length);
                }
            } catch (IOException e) {
                throw new ClassNotFoundException("couldn't load " + className, e);
            }
        } else {
            throw new IllegalStateException();
//            return super.findClass(className);
        }
    }

    private byte[] getInstrumentedBytes(String className, InputStream classBytesStream) throws IOException {
        final ClassReader classReader = new ClassReader(classBytesStream);
        ClassNode classNode = new ClassNode();
        classReader.accept(classNode, 0);

        boolean foundDefaultConstructor = false;
        List<MethodNode> methods = new ArrayList<MethodNode>(classNode.methods);
        for (MethodNode method : methods) {
            String originalName = method.name;
            if (method.name.equals("<init>")) {
                if (method.desc.equals("()V")) foundDefaultConstructor = true;

                method.access |= ACC_PUBLIC;
                convertConstructorToRegularMethod(method);
                method.name = CONSTRUCTOR_METHOD_NAME;
                classNode.methods.add(generateConstructorMethod(classNode, method));
            } else if (method.name.equals("<clinit>")) {
                method.name = STATIC_INITIALIZER_METHOD_NAME;
                classNode.methods.add(generateStaticInitializerNotifierMethod(className));
            } else {
                method.name = MethodGenerator.directMethodName(className, method.name);
                classNode.methods.add(generateInstrumentedMethod(className, method, originalName));
            }
        }

        classNode.fields.add(new FieldNode(ACC_PUBLIC, CLASS_HANDLER_DATA_FIELD_NAME, OBJECT_DESC, OBJECT_DESC, null));

        if (!foundDefaultConstructor) {
            MethodNode defaultConstructor = new MethodNode(ACC_PUBLIC, "<init>", "()V", "()V", null);
            MyGenerator m = new MyGenerator(defaultConstructor);
            m.loadThis();
            m.visitMethodInsn(INVOKESPECIAL, classNode.superName, "<init>", "()V");
            m.returnValue();
            m.endMethod();
            classNode.methods.add(defaultConstructor);
        }

        ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        classNode.accept(classWriter);

        byte[] classBytes = classWriter.toByteArray();
        if (debug || className.contains("Example")) {
            new ClassReader(classBytes).accept(new TraceClassVisitor(new PrintWriter(System.out)), 0);
        }
        return classBytes;
    }

    private void convertConstructorToRegularMethod(MethodNode cons) {
        InsnList ins = cons.instructions;
        ListIterator li = ins.iterator();
        // Look for the ALOAD 0 (i.e., push this on the stack)
        while (li.hasNext()) {
            AbstractInsnNode node = (AbstractInsnNode) li.next();
            if (node.getOpcode() == ALOAD) {
                VarInsnNode varNode = (VarInsnNode) node;
                assert varNode.var == 0;
                // Remove the ALOAD
                li.remove();
                break;
            }
        }

        // Look for the call to the super-class, an INVOKESPECIAL
        while (li.hasNext()) {
            AbstractInsnNode node = (AbstractInsnNode) li.next();
            if (node.getOpcode() == INVOKESPECIAL) {
                MethodInsnNode mnode = (MethodInsnNode) node;
//                assert mnode.owner.equals(methodNo.superName);
                assert mnode.name.equals("<init>");
//                assert mnode.desc.equals(cons.desc);

                li.remove();
                return;
            }
        }

        throw new AssertionError("Could not convert constructor to simple method.");
    }

    private MethodNode generateInstrumentedMethod(String className, MethodNode directMethod, String originalName) {
        String[] exceptions = ((List<String>) directMethod.exceptions).toArray(new String[directMethod.exceptions.size()]);
        MethodNode methodNode = new MethodNode(directMethod.access, originalName, directMethod.desc, directMethod.signature, exceptions);
        methodNode.access &= ~(ACC_NATIVE | ACC_ABSTRACT);

        MyGenerator m = new MyGenerator(methodNode);
        String classRef = classRef(className);
        Type classType = getType(classRef);

        Label callDirect = new Label();
        Label callClassHandler = new Label();

        if (!m.isStatic) {
            m.loadThis();                                         // this
            m.getField(classType, "__robo_data__", OBJECT_TYPE);  // contents of __robo_data__
            m.instanceOf(classType);                              // is instance of same class?
            m.visitJumpInsn(IFNE, callDirect); // jump if yes (is instance)
        }

        m.loadThisOrNull();                                       // this
        m.invokeStatic(ROBOLECTRIC_INTERNALS_TYPE, new Method("shouldCallDirectly", "(Ljava/lang/Object;)Z"));
        // args, should call directly?
        m.visitJumpInsn(IFEQ, callClassHandler); // jump if no (should not call directly)

        // callDirect...
        m.mark(callDirect);

        // call direct method and return
        m.loadThisOrNull();                                       // this
        m.loadArgs();                                             // this, [args]
        m.visitMethodInsn(INVOKESPECIAL, classRef, originalName, directMethod.desc);
        m.returnValue();

        // callClassHandler...
        m.mark(callClassHandler);
        generateCallToClassHandler(directMethod, originalName, m);

        m.unbox(m.getReturnType());
        m.returnValue();

        m.endMethod();

        return methodNode;
    }

    private MethodNode generateStaticInitializerNotifierMethod(String className) {
        MethodNode methodNode = new MethodNode(ACC_STATIC, "<clinit>", "()V", "()V", null);
        MyGenerator m = new MyGenerator(methodNode);
        m.push(Type.getObjectType(classRef(className)));
        m.invokeStatic(Type.getType(RobolectricInternals.class), new Method("classInitializing", "(Ljava/lang/Class;)V"));
        m.returnValue();
        m.endMethod();
        return methodNode;
    }

    private MethodNode generateConstructorMethod(ClassNode classNode, MethodNode directMethod) {
        String[] exceptions = ((List<String>) directMethod.exceptions).toArray(new String[directMethod.exceptions.size()]);
        MethodNode methodNode = new MethodNode(directMethod.access, "<init>", directMethod.desc, directMethod.signature, exceptions);
        MyGenerator m = new MyGenerator(methodNode);

        // call super()
        m.loadThis();                                             // this
        m.visitMethodInsn(INVOKESPECIAL, classNode.superName, "<init>", "()V");

        generateCallToClassHandler(directMethod, "__constructor__", m);

        m.unbox(m.getReturnType());
        m.returnValue();

        m.endMethod();
        return methodNode;
    }

    private void generateCallToClassHandler(MethodNode directMethod, String originalMethodName, MyGenerator m) {
        // prepare for call to classHandler.methodInvoked()
        m.loadThisOrNull();                                       // this
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;");
        // my class
        m.push(originalMethodName);                                     // my class, method name
        m.loadThisOrNull();                                       // my class, method name, this
//
//            // load param types
        Type[] argumentTypes = Type.getArgumentTypes(directMethod.desc);
        m.push(argumentTypes.length);
        m.newArray(STRING_TYPE);                                   // my class, method name, this, String[n]{nulls}
        for (int i = 0; i < argumentTypes.length; i++) {
            Type argumentType = argumentTypes[i];
            m.dup();
            m.push(i);
            m.push(argumentType.getClassName());
            m.arrayStore(STRING_TYPE);
        }
        // my class, method name, this, String[n]{param class names}

        m.loadArgArray();

        m.invokeStatic(ROBOLECTRIC_INTERNALS_TYPE, new Method("methodInvoked", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;"));
    }

    private String classRef(String className) {
        return className.replace('.', '/');
    }

    private static byte[] readBytes(InputStream classBytesStream) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int c;
        while ((c = classBytesStream.read()) != -1) {
            baos.write(c);
        }
        return baos.toByteArray();
    }

    private static class MyGenerator extends GeneratorAdapter {
        private final boolean isStatic;
        private final String desc;

        public MyGenerator(MethodNode methodNode) {
            super(Opcodes.ASM4, methodNode, methodNode.access, methodNode.name, methodNode.desc);
            this.isStatic = Modifier.isStatic(methodNode.access);
            this.desc = methodNode.desc;
        }

        public void loadThisOrNull() {
            if (isStatic) {
                loadNull();
            } else {
                loadThis();
            }
        }

        public boolean isStatic() {
            return isStatic;
        }

        public void loadNull() {
            visitInsn(ACONST_NULL);
        }

        public Type getReturnType() {
            return Type.getReturnType(desc);
        }
    }
}