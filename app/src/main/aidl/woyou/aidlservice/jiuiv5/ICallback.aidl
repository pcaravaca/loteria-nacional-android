// Verified against the device: SunmiPrinterService_v4.4.1_V2_release.apk
package woyou.aidlservice.jiuiv5;

interface ICallback {
    void onPrintResult(int code, String message);
    void onRaiseException(int code, String message);
    void onReturnString(String value);
    void onRunResult(boolean success);
}
