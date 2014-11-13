package arksea.jleaks;

/**
 * 用于显式释放资源的接口
 */
public interface IDisposable {

    boolean isDisposed();

    void dispose();
}
