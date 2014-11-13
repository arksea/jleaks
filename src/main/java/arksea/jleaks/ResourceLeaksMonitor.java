package arksea.jleaks;

import java.lang.ref.*;
import java.lang.reflect.Field;
import java.util.HashMap;
import arksea.jactor.*;
import java.io.Closeable;
import org.apache.log4j.xml.DOMConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//资源的幽灵引用
class ResourceReference extends PhantomReference {

    private static Field referentField = null;
    private final static Logger logger = LoggerFactory.getLogger(ResourceReference.class.getName());

    static {
        try {
            referentField = Reference.class.getDeclaredField("referent");
            referentField.setAccessible(true);
        } catch (Exception ex) {
            logger.error("set referent accessible true failed", ex);
        }
    }

    public ResourceReference(Object referent, ReferenceQueue q) {
        super(referent, q);
    }

    @Override
    public Object get() {
        Object ret = null;
        try {
            ret = referentField.get(this);
        } catch (Exception ex) {
            logger.error("get resource referent failed", ex);
        }
        return ret;
    }
}

public class ResourceLeaksMonitor extends Actor<ResourceLeaksMonitor.State> {

    /**
     * 停止资源回收检测
     */
    public synchronized static void stopMonitor() {
        TaskContext.instance().stop("res_leaks_monitor_sup");
    }

    /**
     * 启动资源回收检测
     * @param second long　检测间隔时间
     */
    public synchronized static void startMonitor(long delaySecond) {
        stopMonitor();
        ChildInfo[] childs = new ChildInfo[1];
        State state = new State(delaySecond);
        childs[0] = new ChildInfo(ResourceLeaksMonitor.TASK_NAME, ResourceLeaksMonitor.class, state, 1000, true);
        TaskContext.instance().start("res_leaks_monitor_sup", RestartStrategies.ONE_FOR_ONE, childs);
    }
    //---------------------------------------------------------------------------
    //以下系列方法为导出的API，
    //这些静态方法仅是为了简化TaskContext.call()的使用,
    //在MessageChildTask中将会频繁看到这种范式
    public static String TASK_NAME = "resource_leaks_monitor";
    public static long DEFAULT_TIMEOUT = 10000;

    private static class RegArg {

        Object obj;
        String disposeMethod;
        Class iface;
        Exception ex;
    }

    /**
     * 向资源回收检测器注册对象，用于注册一个没有实现IDisposable接口的资源，
     * 通常用于工厂方法，创建一个对象，并返回其监控代理
     * @param disposeMethod String 释放资源的方法
     * @param obj Object　 被监控对象
     * @param iface Class　对象实现的接口
     * @return Object　    被监控对象的代理
     */
    public static Object register(Object obj, String disposeMethod, Class iface) {
        try {
            assert (iface.isInterface());
            assert (!IDisposable.class.isAssignableFrom(obj.getClass()));
            if (!TaskContext.instance().exist(TASK_NAME)) {
                return obj; //没有启动资源泄露监控则直接返回对象本身
            }
            RegArg arg = new RegArg();
            arg.obj = obj;
            arg.disposeMethod = disposeMethod;
            arg.iface = iface;
            arg.ex = new Exception("资源初始化跟踪信息");
            Message ret = context.call(TASK_NAME, new Message("reg_obj", arg), DEFAULT_TIMEOUT);
            return ret.value;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * 向资源回收检测器注册对象，用于注册一个实现了IDisposable接口的对象，
     * 通常在被监控类的构造函数中调用，
     * @param resource Object
     * @param obj Object　待测资源
     * @param iface Class　资源实现的接口
     */
    public static void register(IDisposable obj) {
        try {
            if (!TaskContext.instance().exist(TASK_NAME)) {
                return;
            }
            RegArg arg = new RegArg();
            arg.obj = obj;
            arg.ex = new Exception("资源初始化跟踪信息");
            context.call(TASK_NAME, new Message("reg_disposable_obj", arg), DEFAULT_TIMEOUT);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
    //---------------------------------------------------------------------------
    private final static Logger logger = LoggerFactory.getLogger(ResourceLeaksMonitor.class.getName());
    private static final TaskContext context = TaskContext.instance();

    public static class State {

        private final HashMap<ResourceReference, Exception> resources = new HashMap();
        private ReferenceQueue reapedQueue = new ReferenceQueue();
        private final Long delaySecond;

        private State(Long delaySecond) {
            this.delaySecond = delaySecond;
        }
    }

    public ResourceLeaksMonitor(String name, long maxQueueLen, State state) {
        super(name, maxQueueLen, state);
    }

    void checkResource() {
        ResourceReference ref;
        Object obj;
        Class cls;
        while ((ref = (ResourceReference) state.reapedQueue.poll()) != null) {
            obj = ref.get();
            cls = obj.getClass();
            Exception info = state.resources.remove(ref);
            //以下检测未IDisposable接口的对象
            if (IResourceProxy.class.isAssignableFrom(cls)) {
                IResourceProxy proxy = (IResourceProxy) obj;
                if (!proxy.isDisposed()) {
                    String msg = "资源泄漏警告,使用完" + proxy.getResourceClassName() +
                            "后未调用" + proxy.getDisposeMethod() + "()进行资源释放";
                    logger.error(msg, info);
                    proxy.dispose();
                }
            } else {
                IDisposable dis = (IDisposable) obj;
                if (!dis.isDisposed()) {
                    String msg = "资源泄漏警告,使用完" + cls.getName() +
                            "后未调用despose()进行资源释放";
                    logger.error(msg, info);
                    dis.dispose();
                }
            }
        }
    }

    @Override
    protected void handle_info(Message msg, String from) throws Throwable {
        String key = msg.name;
        if (key.equals("check")) {
            System.gc();
            this.checkResource();
            Long delay = state.delaySecond * 1000;
            context.send_after(delay, TASK_NAME, new Message("check", ""));
        } else {
            logger.error("unknown message :: " + msg);
        }
    }

    @Override
    protected Message handle_call(Message msg, String from) throws Throwable {
        String key = msg.name;
        if (key.equals("reg_disposable_obj")) {
            RegArg arg = (RegArg) msg.value;
            ResourceReference ref = new ResourceReference(arg.obj, state.reapedQueue);
            state.resources.put(ref, arg.ex);
            return new Message("ok", arg.obj);
        } else if (key.equals("reg_obj")) {
            RegArg arg = (RegArg) msg.value;
            Object resource = ResourceProxy.newInstance(arg.obj, arg.disposeMethod, arg.iface);
            ResourceReference ref = new ResourceReference(resource, state.reapedQueue);
            state.resources.put(ref, arg.ex);
            return new Message("ok", resource);
        }
        return null;
    }

    @Override
    protected void init() throws Throwable {
        context.send_after(1000, TASK_NAME, new Message("check", ""));
    }

    @Override
    protected void terminate(Throwable ex) {
        try {
            state.resources.clear();
            while ((ResourceReference) state.reapedQueue.poll() != null) {
                state.reapedQueue.remove();
            }
        } catch (InterruptedException e) {
        }
    }

    public static void main(String[] args) {
        DOMConfigurator.configure("log4j.xml");
        startMonitor(1);
        try {
            TestResource1 tr1 = new TestResource1();
            TestResource2 tr2 = new TestResource2();
            ResourceLeaksMonitor.register(tr2, "close", Closeable.class);
            //tr1.dispose();
            //tr2.close();
            tr1 = null;
            tr2 = null;
        } catch (Exception e) {
            logger.error("error", e);
        }
        try {
            Thread.sleep(5000);
        } catch (Exception e) {
        }
        stopMonitor();
    }
}

class TestResource1 implements IDisposable {

    public TestResource1() {
        ResourceLeaksMonitor.register(this);
    }
    private volatile boolean disposed = false;

    @Override
    public final boolean isDisposed() {
        return disposed;
    }

    @Override
    public void dispose() {
        disposed = true;
    }
}

class TestResource2 implements Closeable {

    public TestResource2() {

    }

    @Override
    public void close() {
    }
}

