package arksea.jleaks;

/**
 * 资源代理接口
 */
public interface IResourceProxy extends IDisposable {

    String getResourceClassName();

    String getDisposeMethod();
}
