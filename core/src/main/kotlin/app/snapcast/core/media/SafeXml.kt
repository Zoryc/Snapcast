package app.snapcast.core.media

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * DOM parsing hardened against XXE and entity-expansion attacks. Manifests and subtitle files never
 * need a DTD, so documents declaring one are rejected outright; the factory is additionally configured
 * to refuse DTDs and external entities. Each setting is applied on its own because Android's parser
 * does not support all of them.
 *
 * Documents nested deeper than [MAX_DEPTH] are refused as well: Android's parser and the code that walks
 * the tree recurse once per level, so a page could otherwise end the app with a stack overflow.
 */
object SafeXml {
    private val DTD_MARKER = Regex("<!DOCTYPE|<!ENTITY", RegexOption.IGNORE_CASE)

    /** Real manifests and subtitle files stay under a dozen levels. */
    private const val MAX_DEPTH = 64

    /**
     * Parses [xml] into a namespace-aware DOM, or returns null when it is malformed, declares a DTD or is
     * nested too deeply.
     */
    fun parse(xml: String): Document? {
        val text = xml.removePrefix("﻿")
        if (text.isBlank() || DTD_MARKER.containsMatchIn(text)) return null
        return try {
            newDocumentBuilder().parse(InputSource(StringReader(text))).takeIf(::isShallow)
        } catch (e: Exception) {
            null
        } catch (e: StackOverflowError) {
            null
        }
    }

    /** Walks the tree without recursion. */
    private fun isShallow(document: Document): Boolean {
        var node: Node? = document.documentElement
        var depth = 1
        while (node != null) {
            if (depth > MAX_DEPTH) return false
            val child = node.firstChild
            if (child != null) {
                node = child
                depth++
            } else {
                while (node != null && node.nextSibling == null) {
                    node = node.parentNode
                    depth--
                }
                node = node?.nextSibling
            }
        }
        return true
    }

    /**
     * A builder that refuses DTDs where the platform allows it, never resolves external entities
     * (they resolve to empty input) and reports errors by throwing.
     */
    fun newDocumentBuilder(): DocumentBuilder {
        val builder = factory().newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        builder.setErrorHandler(SilentErrorHandler)
        return builder
    }

    private fun factory(): DocumentBuilderFactory {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        attempt { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        attempt { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        attempt { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        attempt { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        attempt { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        attempt { factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
        attempt { factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
        attempt { factory.isExpandEntityReferences = false }
        attempt { factory.isXIncludeAware = false }
        attempt { factory.isValidating = false }
        return factory
    }

    private inline fun attempt(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // Unsupported by this platform's parser; the DTD pre-check above still applies.
        } catch (e: AbstractMethodError) {
            // Older parser implementations do not implement every JAXP setter.
        }
    }

    /** Reports problems by throwing instead of printing "[Fatal Error]" lines to stderr. */
    private object SilentErrorHandler : ErrorHandler {
        override fun warning(exception: SAXParseException) = Unit
        override fun error(exception: SAXParseException) = throw exception
        override fun fatalError(exception: SAXParseException) = throw exception
    }
}

/** Element name without namespace prefix. */
internal val Element.localTag: String
    get() = localName ?: nodeName.substringAfter(':')

/** Direct child elements, optionally only those with the given local name. */
internal fun Element.childElements(localName: String? = null): List<Element> {
    val result = mutableListOf<Element>()
    val nodes = childNodes
    for (i in 0 until nodes.length) {
        val node = nodes.item(i)
        if (node.nodeType == Node.ELEMENT_NODE) {
            val element = node as Element
            if (localName == null || element.localTag == localName) result += element
        }
    }
    return result
}

/** All descendant elements with the given local name, in document order. */
internal fun Element.descendants(localName: String): List<Element> {
    val result = mutableListOf<Element>()
    fun visit(element: Element) {
        for (child in element.childElements()) {
            if (child.localTag == localName) result += child
            visit(child)
        }
    }
    visit(this)
    return result
}

/** Attribute value by local name (ignoring any prefix), trimmed; null when absent or blank. */
internal fun Element.attr(localName: String): String? {
    getAttribute(localName).takeIf { it.isNotBlank() }?.let { return it.trim() }
    val attributes = attributes
    for (i in 0 until attributes.length) {
        val node = attributes.item(i)
        val name = node.localName ?: node.nodeName.substringAfter(':')
        if (name == localName) return node.nodeValue?.trim()?.takeIf { it.isNotEmpty() }
    }
    return null
}
