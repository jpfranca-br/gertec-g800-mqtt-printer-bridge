package com.xcheng.printerservice;

// Recovered from the device's PrinterService (com.xcheng.printerservice).
// Declaration order defines transaction codes: onException=1, onLength=2, onStart=3, onComplete=4.
oneway interface IPrinterCallback {
    void onException(int code, String msg);
    void onLength(long current, long total);
    void onStart();
    void onComplete();
}
