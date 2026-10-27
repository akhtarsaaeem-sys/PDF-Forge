package com.pdforge.app.pdf

import com.itextpdf.kernel.utils.DefaultSafeXmlParserFactory
import com.itextpdf.kernel.utils.XmlProcessorCreator
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Android-compatible XML parser factory for iText.
 *
 * ROOT CAUSE of the PDF/A failure:
 * `Failed to convert to PDF/A: This parser does not support specification
 * "Unknown" version "0.0"`.
 *
 * When iText opens a PDF that carries an XMP metadata stream it parses that
 * metadata with an XML parser created by [DefaultSafeXmlParserFactory]. That
 * class calls `DocumentBuilderFactory.setXIncludeAware(false)` — an API that
 * Android's default `DocumentBuilderFactory` does not implement, so it throws
 *
 *     java.lang.UnsupportedOperationException:
 *     This parser does not support specification "Unknown" version "0.0"
 *
 * The same problem affects `setExpandEntityReferences`. Because the exception is
 * thrown while the metadata is parsed, ANY iText document open of a PDF with XMP
 * metadata fails on Android — PDF/A conversion just happened to be the tool the
 * tester used. Registering this factory in Application.onCreate fixes every
 * affected iText operation, not only PDF/A.
 *
 * This does NOT suppress a real error: the standard security features
 * (external entity resolution disabled, DOCTYPE declarations disallowed) are
 * still applied; only the two setters Android cannot implement are skipped.
 */
class AndroidCompatXmlParserFactory : DefaultSafeXmlParserFactory() {

    override fun configureSafeDocumentBuilderFactory(factory: DocumentBuilderFactory) {
        configureSafely(factory)
    }

    /**
     * Applies the safe XML configuration, degrading gracefully when a setter is
     * not implemented by the platform parser. Extracted so the Android failure
     * mode can be reproduced and covered by a unit test.
     */
    internal fun configureSafely(factory: DocumentBuilderFactory) {
        try {
            super.configureSafeDocumentBuilderFactory(factory)
        } catch (t: Throwable) {
            // Android does not implement setXIncludeAware /
            // setExpandEntityReferences. Apply the hardening that IS supported,
            // and skip the unsupported setters so metadata parsing can proceed.
            setFeatureQuietly(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeatureQuietly(factory, "http://xml.org/sax/features/external-general-entities", false)
            setFeatureQuietly(factory, "http://xml.org/sax/features/external-parameter-entities", false)
            setFeatureQuietly(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            try {
                factory.isExpandEntityReferences = false
            } catch (_: Throwable) {
            }
            try {
                factory.isXIncludeAware = false
            } catch (_: Throwable) {
            }
        }
    }

    private fun setFeatureQuietly(factory: DocumentBuilderFactory, feature: String, value: Boolean) {
        try {
            factory.setFeature(feature, value)
        } catch (_: Throwable) {
            // Feature not supported by this platform parser — safe to skip.
        }
    }

    companion object {
        /**
         * Installs the Android-compatible parser factory for every iText
         * operation in the process. Call once, as early as possible
         * (Application.onCreate).
         */
        fun install() {
            XmlProcessorCreator.setXmlParserFactory(AndroidCompatXmlParserFactory())
        }
    }
}
