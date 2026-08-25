package com.litiansuo.purifier.hook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 反射工具。
 *
 * <p>为什么要自己写：libxposed API 102 起，模块<b>不允许</b>再调用
 * {@code de.robv.android.xposed} 下的任何东西，所以旧项目里顺手的
 * {@code XposedHelpers.findAndHookMethod / callMethod / getObjectField} 在这里全部不可用。
 * 新 API 只提供 {@code hook(Executable)}，「怎么找到那个 Executable」得自己解决。</p>
 *
 * <p>设计原则：</p>
 * <ul>
 *   <li>找不到就返回 {@code null} 或抛 {@link NoSuchMethodException}，由
 *       {@link FeatureGuard} 统一降级，不在这里吞异常；</li>
 *   <li>提供「多候选名」查找，目标应用改版换类名时不至于整项失效；</li>
 *   <li>提供按「方法名 + 参数个数」查找，用于参数类型被混淆、写不出精确签名的场合。</li>
 * </ul>
 */
public final class Reflect {

    private Reflect() {
    }

    // ---------------------------------------------------------------- 类查找

    /** 加载类，失败返回 null。 */
    public static Class<?> findClass(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 按顺序尝试多个候选类名，返回第一个存在的。
     *
     * <p>用于目标 SDK 在不同版本里换过包名的情况，避免把版本差异写成硬编码。</p>
     */
    public static Class<?> findClassAny(ClassLoader cl, String... names) {
        for (String n : names) {
            Class<?> c = findClass(cl, n);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    /** 类是否存在。用于判断目标应用是否真的集成了某个广告 SDK。 */
    public static boolean hasClass(ClassLoader cl, String name) {
        return findClass(cl, name) != null;
    }

    // ---------------------------------------------------------------- 方法查找

    /**
     * 精确签名查找，含父类。
     *
     * @throws NoSuchMethodException 找不到时抛出，交由 FeatureGuard 降级
     */
    public static Method method(Class<?> cls, String name, Class<?>... params)
            throws NoSuchMethodException {
        Class<?> c = cls;
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchMethodException(cls.getName() + "#" + name + " params=" + params.length);
    }

    /**
     * 按方法名 + 参数个数查找。
     *
     * <p>参数类型被混淆时用这个。若同名同参数个数的重载不止一个，说明这个特征不足以定位，
     * 直接抛异常而不是随便挑一个——猜错会 hook 到无关方法，症状极难排查。</p>
     */
    public static Method methodByArity(Class<?> cls, String name, int arity)
            throws NoSuchMethodException {
        List<Method> found = new ArrayList<>(2);
        Class<?> c = cls;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == arity) {
                    found.add(m);
                }
            }
            if (!found.isEmpty()) {
                break;
            }
            c = c.getSuperclass();
        }
        if (found.isEmpty()) {
            throw new NoSuchMethodException(cls.getName() + "#" + name + "/" + arity + " not found");
        }
        if (found.size() > 1) {
            throw new NoSuchMethodException(cls.getName() + "#" + name + "/" + arity
                    + " ambiguous (" + found.size() + " overloads), need exact signature");
        }
        Method m = found.get(0);
        m.setAccessible(true);
        return m;
    }

    /**
     * 找出所有「返回指定类型」的无参方法。
     *
     * <p>用于定位 getter 被混淆成单字母、但返回类型仍是可辨认业务类型的场合。</p>
     */
    public static List<Method> methodsReturning(Class<?> cls, Class<?> returnType, int arity) {
        List<Method> out = new ArrayList<>();
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getParameterCount() == arity && returnType.equals(m.getReturnType())) {
                m.setAccessible(true);
                out.add(m);
            }
        }
        return out;
    }

    /** 找出类里所有同名方法（不限参数），用于需要一次性 hook 全部重载的场合。 */
    public static List<Method> methodsNamed(Class<?> cls, String name) {
        List<Method> out = new ArrayList<>();
        Class<?> c = cls;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    m.setAccessible(true);
                    out.add(m);
                }
            }
            if (!out.isEmpty()) {
                break;
            }
            c = c.getSuperclass();
        }
        return out;
    }

    // ---------------------------------------------------------------- 构造函数

    public static Constructor<?> ctor(Class<?> cls, Class<?>... params) throws NoSuchMethodException {
        Constructor<?> ct = cls.getDeclaredConstructor(params);
        ct.setAccessible(true);
        return ct;
    }

    /** 按参数个数找构造函数，重载不唯一时抛异常。 */
    public static Constructor<?> ctorByArity(Class<?> cls, int arity) throws NoSuchMethodException {
        List<Constructor<?>> found = new ArrayList<>(2);
        for (Constructor<?> ct : cls.getDeclaredConstructors()) {
            if (ct.getParameterCount() == arity) {
                found.add(ct);
            }
        }
        if (found.isEmpty()) {
            throw new NoSuchMethodException(cls.getName() + "#<init>/" + arity + " not found");
        }
        if (found.size() > 1) {
            throw new NoSuchMethodException(cls.getName() + "#<init>/" + arity
                    + " ambiguous (" + found.size() + " overloads)");
        }
        Constructor<?> ct = found.get(0);
        ct.setAccessible(true);
        return ct;
    }

    // ---------------------------------------------------------------- 字段读写

    public static Field field(Class<?> cls, String name) throws NoSuchFieldException {
        Class<?> c = cls;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(cls.getName() + "#" + name);
    }

    /** 找出第一个指定类型的实例字段，用于字段名被混淆但类型可辨认的场合。 */
    public static Field fieldOfType(Class<?> cls, Class<?> type) throws NoSuchFieldException {
        Class<?> c = cls;
        while (c != null) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && type.equals(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            }
            c = c.getSuperclass();
        }
        throw new NoSuchFieldException(cls.getName() + " has no instance field of " + type.getName());
    }

    /** 读实例字段，失败返回 null——调用点通常只是「能读到就用」。 */
    public static Object get(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            return field(target.getClass(), name).get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 写实例字段，返回是否成功。 */
    public static boolean set(Object target, String name, Object value) {
        if (target == null) {
            return false;
        }
        try {
            field(target.getClass(), name).set(target, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读静态字段，失败返回 null。 */
    public static Object getStatic(Class<?> cls, String name) {
        try {
            return field(cls, name).get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 调用

    /** 反射调用无参方法，失败返回 null。 */
    public static Object call(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            return method(target.getClass(), name).invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }
}
