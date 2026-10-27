package com.pdforge.app

import android.app.Application
import com.pdforge.app.pdf.AndroidCompatXmlParserFactory

class PDFForgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // Install iText's Android-compatible XML parser factory before any PDF is
        // opened. Android's DocumentBuilderFactory does not implement
        // setXIncludeAware, which made iText throw
        //   "This parser does not support specification \"Unknown\" version \"0.0\""
        // whenever it parsed a PDF's XMP metadata (e.g. PDF → PDF/A).
        AndroidCompatXmlParserFactory.install()
    }

    companion object {
        lateinit var instance: PDFForgeApp
            private set
    }
}
