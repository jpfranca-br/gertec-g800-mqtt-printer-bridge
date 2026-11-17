package com.xcheng.printerservice;

import android.graphics.Bitmap;
import com.xcheng.printerservice.IPrinterCallback;

// Recovered from the device's PrinterService (com.xcheng.printerservice) on a Gertec Smart G800, Android 8.1.
// DO NOT REORDER: declaration order defines the binder transaction codes (1..17).
interface IPrinterService {
    void upgradePrinter();                                                                 // 1
    String getFirmwareVersion();                                                           // 2
    String getBootloaderVersion();                                                         // 3
    void printerInit(IPrinterCallback cb);                                                 // 4
    void printerReset(IPrinterCallback cb);                                                // 5
    void printWrapPaper(int lines, IPrinterCallback cb);                                   // 6
    void printText(String text, IPrinterCallback cb);                                      // 7
    void printTextWithAttributes(String text, in Map attributes, IPrinterCallback cb);     // 8
    void printColumnsTextWithAttributes(in String[] texts, in List attributes, IPrinterCallback cb); // 9
    void printBitmap(in Bitmap bitmap, IPrinterCallback cb);                               // 10
    void printBitmapWithAttributes(in Bitmap bitmap, in Map attributes, IPrinterCallback cb); // 11
    void printBarCode(String data, int a, int b, int c, boolean d, IPrinterCallback cb);   // 12
    void printQRCode(String data, int a, int b, IPrinterCallback cb);                      // 13
    void setPrinterSpeed(int level, IPrinterCallback cb);                                  // 14
    void sendRAWData(in byte[] data, IPrinterCallback cb);                                 // 15
    int printerTemperature(IPrinterCallback cb);                                           // 16
    boolean printerPaper(IPrinterCallback cb);                                             // 17
}
