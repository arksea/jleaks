package arksea.jleaks;

import java.lang.reflect.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 资源代理类: 代理没有实现IDisposeable接口的资源
 */
public class ResourceProxy implements InvocationHandler, IResourceProxy {

    private volatile boolean disposed;
    private final Object resource;
    private String disposeMethod;
    private final static Logger logger = LoggerFactory.getLogger(ResourceProxy.class.getName());

    private ResourceProxy(Object resource, String method) {
        this.resource = resource;
        this.disposeMethod = method;
        this.disposed = false;
    }

    public String getDisposeMethod() {
        return disposeMethod;
    }

    public String getResourceClassName() {
        return resource.getClass().getName();
    }

    public static Object newInstance(Object resource, String disposeMethod, Class interfac) {
        try {
            Class proxyClass = Proxy.getProxyClass(
                    Thread.currentThread().getContextClassLoader(),
                    new Class[]{interfac, IResourceProxy.class});
            Constructor con = proxyClass.getConstructor(
                    new Class[]{InvocationHandler.class});
            return con.newInstance(new Object[]{new ResourceProxy(resource, disposeMethod)});
        } catch (Exception e) //catch (InvocationTargetException ex)
        //catch (IllegalAccessException ex)
        //catch (InstantiationException ex)
        //catch (SecurityException ex)
        //catch (NoSuchMethodException ex)
        //catch (IllegalArgumentException ex)
        {
            throw new RuntimeException("创建资源代理类失败", e);
        }
    }

    public Object invoke(Object proxy, Method method, Object[] args)
            throws Throwable {
        String methodName = method.getName();
        //以下调用代理类的方法
        if (methodName.equals("isDisposed")) {
            return this.isDisposed();
        } else if (methodName.equals("getResourceClassName")) {
            return this.getResourceClassName();
        } else if (methodName.equals("getDisposeMethod")) {
            return this.getDisposeMethod();
        } else if (methodName.equals("dispose")) {
            return method.invoke(this, args);
        }
        //以下调用实际的资源对象的方法
        if (methodName.equals(disposeMethod)) {   //在执行指定的清理方法前先将disposed设置为true
            this.disposed = true;
        }
        return method.invoke(resource, args);
    }

    public boolean isDisposed() {
        return this.disposed;
    }

    public void dispose() {
        try {
            Method method = resource.getClass().getMethod(disposeMethod);
            method.invoke(resource);
        } catch (Exception ex) {
            logger.error("dispose failed", ex);
        }
    }
}
