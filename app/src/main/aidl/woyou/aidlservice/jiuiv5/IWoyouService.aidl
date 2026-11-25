// Reverse-engineered from the installed Sunmi printer service
// (/system/app/SunmiPrinterService_v4.4.1_V2_release).
// Method order == AIDL transaction id order (1..44). Verified by dexdump.
package woyou.aidlservice.jiuiv5;

import android.graphics.Bitmap;
import woyou.aidlservice.jiuiv5.ICallback;
import woyou.aidlservice.jiuiv5.ITax;

interface IWoyouService {
    boolean postPrintData(String data, in byte[] extra, int a, int b);
    int getFirmwareStatus();
    String getServiceVersion();
    void printerInit(ICallback callback);
    void printerSelfChecking(ICallback callback);
    String getPrinterSerialNo();
    String getPrinterVersion();
    String getPrinterModal();
    void getPrintedLength(ICallback callback);
    void lineWrap(int lines, ICallback callback);
    void sendRAWData(in byte[] data, ICallback callback);
    void setAlignment(int align, ICallback callback);
    void setFontName(String name, ICallback callback);
    void setFontSize(float size, ICallback callback);
    void printText(String text, ICallback callback);
    void printTextWithFont(String text, String font, float size, ICallback callback);
    void printColumnsText(in String[] text, in int[] width, in int[] align, ICallback callback);
    void printBitmap(in Bitmap bitmap, ICallback callback);
    void printBarCode(String barcode, int symbology, int height, int width, int align, ICallback callback);
    void printQRCode(String qrCode, int size, int align, ICallback callback);
    void printOriginalText(String text, ICallback callback);
    // Slot 22 in the real contract is commitPrint(TransBean[], ICallback). This app never
    // calls it; Bitmap[] is a stand-in purely to keep the transaction ids aligned.
    void commitPrint(in Bitmap[] standIn, ICallback callback);
    void commitPrinterBuffer();
    void enterPrinterBuffer(boolean clear);
    void exitPrinterBuffer(boolean commit);
    void tax(in byte[] data, ITax tax);
    void getPrinterFactory(ICallback callback);
    void clearBuffer();
    void commitPrinterBufferWithCallback(ICallback callback);
    void exitPrinterBufferWithCallback(boolean commit, ICallback callback);
    void printColumnsString(in String[] text, in int[] width, in int[] align, ICallback callback);
    int updatePrinterState();
    void printBitmapCustom(in Bitmap bitmap, int align, ICallback callback);
    int getForcedDouble();
    boolean isForcedAntiWhite();
    boolean isForcedBold();
    boolean isForcedUnderline();
    int getForcedRowHeight();
    int getFontName();
    int getPrinterDensity();
    void print2DCode(String code, int type, int size, int align, ICallback callback);
    int getPrinterPaper();
    void autoOutPaper(ICallback callback);
    void setPrinterStyle(int style, int arg);
}
