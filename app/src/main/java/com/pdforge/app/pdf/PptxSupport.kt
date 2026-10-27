package com.pdforge.app.pdf

import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * OOXML namespace URIs used by PPTX (and shared package relationships).
 */
internal object Ooxml {
    const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val REL = "http://schemas.openxmlformats.org/package/2006/relationships"

    /** English Metric Units per PDF point (914400 EMU per inch / 72 pt per inch). */
    const val EMU_PER_POINT = 12700.0

    /**
     * A namespace-aware DOM parser with external entity resolution disabled.
     *
     * The hardening calls are wrapped individually because not every parser
     * implementation (notably Android's) supports every feature; an unsupported
     * feature must not abort the whole document load.
     */
    fun newDocumentBuilder(): DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        tryQuietly { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        tryQuietly { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        tryQuietly { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        tryQuietly { factory.isXIncludeAware = false }
        return factory.newDocumentBuilder()
    }

    private inline fun tryQuietly(block: () -> Unit) {
        try { block() } catch (_: Throwable) {}
    }

    /** Reads a zip entry's XML into a DOM [Document]. */
    fun parseEntry(zip: ZipFile, entryName: String): Document? {
        val entry = zip.getEntry(entryName) ?: return null
        return zip.getInputStream(entry).use { newDocumentBuilder().parse(it) }
    }

    /**
     * All descendant elements with the given namespace + local name, in document
     * order, scoped to [root] (so searching a paragraph returns only that
     * paragraph's runs).
     */
    fun elements(root: Node, namespace: String, localName: String): List<Element> {
        val result = ArrayList<Element>()
        fun walk(node: Node) {
            var child: Node? = node.firstChild
            while (child != null) {
                if (child is Element && child.namespaceURI == namespace && child.localName == localName) {
                    result.add(child)
                }
                walk(child)
                child = child.nextSibling
            }
        }
        walk(root)
        return result
    }

}

/** A rId → target mapping from a `.rels` part. */
internal data class Relationship(val id: String, val type: String, val target: String)

/** Parses a relationships part (a `.rels` entry) into relationships keyed by id. */
internal fun parseRelationships(zip: ZipFile, relsEntry: String): Map<String, Relationship> {
    val doc = Ooxml.parseEntry(zip, relsEntry) ?: return emptyMap()
    val result = LinkedHashMap<String, Relationship>()
    for (el in Ooxml.elements(doc, Ooxml.REL, "Relationship")) {
        val id = el.getAttribute("Id")
        if (id.isNullOrBlank()) continue
        result[id] = Relationship(
            id = id,
            type = el.getAttribute("Type") ?: "",
            target = el.getAttribute("Target") ?: ""
        )
    }
    return result
}

/** Resolves a relationship target relative to the part's own folder. */
internal fun resolveTarget(partName: String, target: String): String {
    if (target.startsWith("/")) return target.trimStart('/')
    val baseDir = partName.substringBeforeLast('/', "")
    val segments = ArrayList<String>()
    if (baseDir.isNotEmpty()) segments.addAll(baseDir.split('/'))
    for (segment in target.split('/')) {
        when (segment) {
            "", "." -> {}
            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
            else -> segments.add(segment)
        }
    }
    return segments.joinToString("/")
}

/** Writes [entryName] → [content] into an open zip output stream. */
internal fun ZipOutputStream.writeText(entryName: String, content: String) {
    putNextEntry(ZipEntry(entryName))
    write(content.toByteArray(Charsets.UTF_8))
    closeEntry()
}

/** Writes binary [content] for [entryName] into an open zip output stream. */
internal fun ZipOutputStream.writeBytes(entryName: String, content: ByteArray) {
    putNextEntry(ZipEntry(entryName))
    write(content)
    closeEntry()
}
