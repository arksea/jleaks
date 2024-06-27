jleaks
======
    Java Resource Leaks Monitor，用于Java资源泄露检测。
 

  当程序库的作者向用户提供一个使用后需要释放资源的类，通常都苦恼如何保证用户的这个行为。Java的类不像C++拥有析构函数，对于库的提供者，C++程序员面对上述问题只要简单的在析构函数中释放其资源即可，但是Java通常是提供一个close()方法给使用者，要求使用者主动调用去释放资源，但是如果使用者没有调用，作为库本身也没有什么办法。这个资源释放的需求，在复杂的系统中，有时会形成一个链条，任何一个环节用户疏忽了，都会造成之后所有的资源产生泄露。

  jleaks就给程序库提供了检测用户的不正确使用造成的资源泄露的能力。为什么是给程序库提供这种能力，而不是让库的用户直接使用呢？因为如果要求库的使用者直接应用这种功能，你说如果用户不记得通过finally或者JDK7的新特性try-with-resource语法去保障资源的释放，同样会不记得将资源向检测工具类注册呀。但是对于库的提供者，一旦你通过细致的工作，将所有的需要用户释放的资源都进行了注册，那么这种细致将会被“遗传”到整个使用你的类库的所有代码中去，这种保障将是静态的，传播的，不依赖于使用者的。

  使用这个类很简单，只要将资源注册一下即可。如果用户在使用这个资源对象后没有调用清除方法，这个类就会检测到并记录一条带有栈信息的日志去警告使用者，让我们可以很容易的找到资源创建的源头。

###下面将演示这个类的两种使用模式，首先来看模式一。

  假设我们的程序库有一个提供给用户的资源接口，我们可以让这个资源接口继承自IDisposable，并希望用户使用完后调用dispose()方法

```
import arksea.jleaks.IDisposable;
public interface IMyConnection extends IDisposable {
    public void fun1();
    public void fun2();
}
```
  其中用到工具类中的IDisposable接口的定义
```
public interface IDisposable {
    public boolean isDisposed();
    public void dispose();
}
```
  我们只要简单的在MyConnectionImpl实现类的构造函数中，将自身向资源泄露监控类注册一下即可
```
public class MyConnectionImpl implements IMyConnection{
    public MyConnectionImpl(){
        ResourceLeaksMonitor.register(this);
    }
    private volatile boolean disposed = false; //注意保证线程安全

    ......

    @Override
    public boolean isDisposed() {
        return disposed;
    }

    @Override
    public void dispose() {
        this.disposed = true;
        //在此处释放资源
    }
}
```
  好了，就这么简单，现在来看看，当我们将这个类库打包分发给用户使用时，如果用户没有对资源进行释放工作将会发生什么
```
DOMConfigurator.configure("log4j.xml");
//启动资源泄露监控，当然你也可以放在其他的系统初始化封装中，避免直接用户需要自己关心此功能的开启
//参数是检测周期(GC)，这里用1秒只是因为是demo，实际应用中可以配置成30秒或者更长时间
//当然系统上线时这句就可以注释掉了
ResourceLeaksMonitor.startMonitor(1); 
IMyConnection conn2 = new MyConnectionImpl();
conn2.fun1();
conn2.fun2();
//conn2.dispose();  //故意不进行资源释放
conn2 = null;       //模拟资源泄露
Thread.sleep(5000);
```
  运行程序你将得到如下的提示日志：
```
2014-11-13 16:19:34 ERROR arksea.jleaks.ResourceLeaksMonitor.checkResource(ResourceLeaksMonitor.java:183)
资源泄漏警告,使用完arksea.jleaks.demo2.MyConnectionImpl后未调用despose()进行资源释放 ; 
 java.lang.Exception: 资源初始化跟踪信息
	at arksea.jleaks.ResourceLeaksMonitor.register(ResourceLeaksMonitor.java:134)
	at arksea.jleaks.demo2.MyConnectionImpl.<init>(MyConnectionImpl.java:13)
	at arksea.jleaks.demo.Main.main(Main.java:29)
```

###第二种模式
  模式二的使用情景是，这个资源是第三方提供的，并没有实现IDisposable接口
  假设以下是第三方库的接口定义
```
public interface IThirdPartyConnection extends Closeable{
    public void fun1();
    public void fun2();
}
```
  我们可以为用户提供一个资源创建工厂，在工厂中创建资源并进行注册
```
public class ConnectionFactory {
    public static IThirdPartyConnection create() {
        ThirdPartyConnectionImpl conn = new ThirdPartyConnectionImpl();
        return (IThirdPartyConnection)ResourceLeaksMonitor.register(conn, "close", IThirdPartyConnection.class);
    }
}
```
  同样的，下面演示一下没有进行资源释放的结果
```
DOMConfigurator.configure("log4j.xml");
//启动资源泄露监控，上线的时候可以不开启
ResourceLeaksMonitor.startMonitor(1);
IThirdPartyConnection conn1 = ConnectionFactory.create();
conn1.fun1();
conn1.fun2();
//conn1.close(); //故意不进行资源的清理工作
conn1 = null;    //模拟资源泄露
Thread.sleep(5000);
```
  程序运行后的结果
```
2014-11-13 16:19:34 ERROR arksea.jleaks.ResourceLeaksMonitor.checkResource(ResourceLeaksMonitor.java:175)
资源泄漏警告,使用完arksea.jleaks.demo1.ThirdPartyConnectionImpl后未调用close()进行资源释放 ; 
 java.lang.Exception: 资源初始化跟踪信息
	at arksea.jleaks.ResourceLeaksMonitor.register(ResourceLeaksMonitor.java:110)
	at arksea.jleaks.demo1.ConnectionFactory.create(ConnectionFactory.java:14)
	at arksea.jleaks.demo.Main.main(Main.java:25)
```

jleaks库已上传到OSChina的Maven库，可以在项目中直接引用：

###gradle
```
repositories {
    maven { url "http://maven.oschina.net/content/repositories/thirdparty" }
}

dependencies {
    compile "net.arksea:jleaks:1.0.0"
} 
```
###maven
```
<repositories>
  <repository>
    <id>public</id>
    <url>http://maven.oschina.net/content/repositories/thirdparty</url>
  </repository>
</repositories>
 
<dependency>
  <groupId>net.arksea</groupId>
  <artifactId>jleaks</artifactId>
  <version>1.0.0</version>
</dependency>
```

Fuck CSDN Keywords: 习近平 8964
