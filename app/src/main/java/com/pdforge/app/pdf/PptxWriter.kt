package com.pdforge.app.pdf

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * Minimal, self-contained PPTX writer.
 *
 * WHY NOT APACHE POI XSLF:
 * The previous PDF → PowerPoint implementation (and any code that calls
 * `XMLSlideShow`/`XSLFPictureShape` on Android) crashes on a real device because
 * the XSLF usermodel references `java.awt.geom.Rectangle2D` (and friends), which
 * do not exist in Android's runtime. Wrapping the call in `catch (Exception)`
 * does not help either — the failure is a `NoClassDefFoundError`/`Error`, not an
 * `Exception`. This writer builds the OOXML package directly with `java.util.zip`
 * and small XML templates, so it is fully Android-safe and can be exercised by a
 * plain JVM test.
 *
 * The produced package is a standard PPTX: one slide per supplied image, sized
 * to the requested slide dimensions, with a slide master, a blank slide layout
 * and a theme — enough for PowerPoint/LibreOffice/POI to open it without repair.
 */
internal fun writeSlideImagesToPptx(
    slideImages: List<ByteArray>,
    slideWidthPt: Float,
    slideHeightPt: Float,
    imageExtension: String,
    outputPath: String
): Int {
    val extension = imageExtension.trimStart('.').lowercase().ifEmpty { "jpg" }
    val widthEmu = (slideWidthPt * Ooxml.EMU_PER_POINT).roundToInt().coerceAtLeast(1)
    val heightEmu = (slideHeightPt * Ooxml.EMU_PER_POINT).roundToInt().coerceAtLeast(1)
    val slideCount = slideImages.size

    FileOutputStream(outputPath).use { fileOut ->
        ZipOutputStream(fileOut).use { zip ->
            zip.writeText("[Content_Types].xml", contentTypes(slideCount, extension))
            zip.writeText("_rels/.rels", rootRels())
            zip.writeText("ppt/presentation.xml", presentation(widthEmu, heightEmu, slideCount))
            zip.writeText("ppt/_rels/presentation.xml.rels", presentationRels(slideCount))
            zip.writeText("ppt/slideMasters/slideMaster1.xml", SLIDE_MASTER)
            zip.writeText("ppt/slideMasters/_rels/slideMaster1.xml.rels", SLIDE_MASTER_RELS)
            zip.writeText("ppt/slideLayouts/slideLayout1.xml", SLIDE_LAYOUT)
            zip.writeText("ppt/slideLayouts/_rels/slideLayout1.xml.rels", SLIDE_LAYOUT_RELS)
            zip.writeText("ppt/theme/theme1.xml", THEME)

            slideImages.forEachIndexed { index, bytes ->
                val number = index + 1
                zip.writeText("ppt/slides/slide$number.xml", slide(widthEmu, heightEmu))
                zip.writeText("ppt/slides/_rels/slide$number.xml.rels", slideRels(number, extension))
                zip.writeBytes("ppt/media/image$number.$extension", bytes)
            }
        }
    }
    return slideCount
}

private fun contentTypes(slideCount: Int, extension: String): String = buildString {
    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
    append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
    append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
    append("""<Default Extension="xml" ContentType="application/xml"/>""")
    append("""<Default Extension="jpg" ContentType="image/jpeg"/>""")
    append("""<Default Extension="jpeg" ContentType="image/jpeg"/>""")
    append("""<Default Extension="png" ContentType="image/png"/>""")
    append("""<Default Extension="$extension" ContentType="${imageContentType(extension)}"/>""")
    append("""<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>""")
    append("""<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>""")
    append("""<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>""")
    append("""<Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>""")
    for (i in 1..slideCount) {
        append("""<Override PartName="/ppt/slides/slide$i.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>""")
    }
    append("</Types>")
}

private fun imageContentType(extension: String): String = when (extension) {
    "png" -> "image/png"
    else -> "image/jpeg"
}

private fun rootRels(): String =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
        """<Relationships xmlns="${Ooxml.REL}">""" +
        """<Relationship Id="rId1" Type="${Ooxml.R}/officeDocument" Target="ppt/presentation.xml"/>""" +
        """</Relationships>"""

private fun presentation(widthEmu: Int, heightEmu: Int, slideCount: Int): String = buildString {
    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
    append("""<p:presentation xmlns:a="${Ooxml.A}" xmlns:r="${Ooxml.R}" xmlns:p="${Ooxml.P}" saveSubsetFonts="1">""")
    append("""<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>""")
    append("<p:sldIdLst>")
    for (i in 1..slideCount) {
        append("""<p:sldId id="${255 + i}" r:id="rId${i + 1}"/>""")
    }
    append("</p:sldIdLst>")
    append("""<p:sldSz cx="$widthEmu" cy="$heightEmu" type="custom"/>""")
    append("""<p:notesSz cx="$heightEmu" cy="$widthEmu"/>""")
    append("</p:presentation>")
}

private fun presentationRels(slideCount: Int): String = buildString {
    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
    append("""<Relationships xmlns="${Ooxml.REL}">""")
    append("""<Relationship Id="rId1" Type="${Ooxml.R}/slideMaster" Target="slideMasters/slideMaster1.xml"/>""")
    for (i in 1..slideCount) {
        append("""<Relationship Id="rId${i + 1}" Type="${Ooxml.R}/slide" Target="slides/slide$i.xml"/>""")
    }
    append("</Relationships>")
}

private fun slide(widthEmu: Int, heightEmu: Int): String =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
        """<p:sld xmlns:a="${Ooxml.A}" xmlns:r="${Ooxml.R}" xmlns:p="${Ooxml.P}">""" +
        "<p:cSld><p:spTree>" +
        """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>""" +
        """<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>""" +
        "<p:pic>" +
        """<p:nvPicPr><p:cNvPr id="2" name="Slide Image"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>""" +
        """<p:blipFill><a:blip r:embed="rId2"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>""" +
        """<p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$widthEmu" cy="$heightEmu"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr>""" +
        "</p:pic>" +
        "</p:spTree></p:cSld>" +
        """<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>""" +
        "</p:sld>"

private fun slideRels(slideNumber: Int, extension: String): String =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
        """<Relationships xmlns="${Ooxml.REL}">""" +
        """<Relationship Id="rId1" Type="${Ooxml.R}/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>""" +
        """<Relationship Id="rId2" Type="${Ooxml.R}/image" Target="../media/image$slideNumber.$extension"/>""" +
        """</Relationships>"""

private val SLIDE_MASTER: String =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
        """<p:sldMaster xmlns:a="${Ooxml.A}" xmlns:r="${Ooxml.R}" xmlns:p="${Ooxml.P}">""" +
        "<p:cSld>" +
        """<p:bg><p:bgPr><a:solidFill><a:schemeClr val="bg1"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>""" +
        "<p:spTree>" +
        """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>""" +
        """<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>""" +
        "</p:spTree>" +
        "</p:cSld>" +
        """<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>""" +
        """<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst>""" +
        "<p:txStyles>" +
        """<p:titleStyle><a:lvl1pPr algn="l"><a:defRPr sz="4400"/></a:lvl1pPr></p:titleStyle>""" +
        """<p:bodyStyle><a:lvl1pPr><a:defRPr sz="1800"/></a:lvl1pPr></p:bodyStyle>""" +
        """<p:otherStyle><a:defPPr><a:defRPr lang="en-US"/></a:defPPr></p:otherStyle>""" +
        "</p:txStyles>" +
        "</p:sldMaster>"

private val SLIDE_MASTER_RELS: String =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
        """<Relationships xmlns="${Ooxml.REL}">""" +
        """<Relationship Id="rId1" Type="${Ooxml.R}/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>""" +
        """<Relationship Id="rId2" Type="${Ooxml.R}/theme" Target="../theme/theme1.xml"/>""" +
        """</Relationships>"""

private val SLIDE_LAYOUT: String =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
        """<p:sldLayout xmlns:a="${Ooxml.A}" xmlns:r="${Ooxml.R}" xmlns:p="${Ooxml.P}" type="blank" preserve="1">""" +
        """<p:cSld name="Blank"><p:spTree>""" +
        """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>""" +
        """<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>""" +
        "</p:spTree></p:cSld>" +
        """<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>""" +
        "</p:sldLayout>"

private val SLIDE_LAYOUT_RELS: String =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
        """<Relationships xmlns="${Ooxml.REL}">""" +
        """<Relationship Id="rId1" Type="${Ooxml.R}/slideMaster" Target="../slideMasters/slideMaster1.xml"/>""" +
        """</Relationships>"""

private val THEME: String =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
        """<a:theme xmlns:a="${Ooxml.A}" name="Office Theme">""" +
        "<a:themeElements>" +
        """<a:clrScheme name="Office">""" +
        """<a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1>""" +
        """<a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>""" +
        """<a:dk2><a:srgbClr val="44546A"/></a:dk2>""" +
        """<a:lt2><a:srgbClr val="E7E6E6"/></a:lt2>""" +
        """<a:accent1><a:srgbClr val="4472C4"/></a:accent1>""" +
        """<a:accent2><a:srgbClr val="ED7D31"/></a:accent2>""" +
        """<a:accent3><a:srgbClr val="A5A5A5"/></a:accent3>""" +
        """<a:accent4><a:srgbClr val="FFC000"/></a:accent4>""" +
        """<a:accent5><a:srgbClr val="5B9BD5"/></a:accent5>""" +
        """<a:accent6><a:srgbClr val="70AD47"/></a:accent6>""" +
        """<a:hlink><a:srgbClr val="0563C1"/></a:hlink>""" +
        """<a:folHlink><a:srgbClr val="954F72"/></a:folHlink>""" +
        "</a:clrScheme>" +
        """<a:fontScheme name="Office">""" +
        """<a:majorFont><a:latin typeface="Calibri Light"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont>""" +
        """<a:minorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont>""" +
        "</a:fontScheme>" +
        """<a:fmtScheme name="Office">""" +
        """<a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst>""" +
        """<a:lnStyleLst><a:ln w="6350" cap="flat" cmpd="sng" algn="ctr"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/></a:ln><a:ln w="12700" cap="flat" cmpd="sng" algn="ctr"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/></a:ln><a:ln w="19050" cap="flat" cmpd="sng" algn="ctr"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/></a:ln></a:lnStyleLst>""" +
        """<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>""" +
        """<a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst>""" +
        "</a:fmtScheme>" +
        "</a:themeElements>" +
        "</a:theme>"
