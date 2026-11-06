package com.example.util

import com.example.data.model.ColorLabel
import com.example.data.model.CullStatus
import com.example.data.model.RawPhotoItem

object XmpSidecarGenerator {

    fun generateXmp(photo: RawPhotoItem): String {
        val rating = photo.rating
        val label = when (photo.colorLabel) {
            ColorLabel.RED -> "Red"
            ColorLabel.YELLOW -> "Yellow"
            ColorLabel.GREEN -> "Green"
            ColorLabel.BLUE -> "Blue"
            ColorLabel.PURPLE -> "Purple"
            ColorLabel.NONE -> ""
        }

        val pickStatus = when (photo.cullStatus) {
            CullStatus.PICK -> 1
            CullStatus.REJECT -> -1
            CullStatus.UNFLAGGED -> 0
        }

        val tagsBag = if (photo.tags.isNotEmpty()) {
            val bagItems = photo.tags.joinToString("\n") { "     <rdf:li>$it</rdf:li>" }
            """
   <dc:subject>
    <rdf:Bag>
$bagItems
    </rdf:Bag>
   </dc:subject>"""
        } else ""

        return """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Lumina RAW DAM 1.0">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:xmp="http://ns.adobe.com/xap/1.0/"
    xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/"
    xmlns:dc="http://purl.org/dc/elements/1.1/"
    xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
    xmlns:xmpDM="http://ns.adobe.com/xmp/1.0/DynamicMedia/"
    xmp:Rating="$rating"
    xmp:Label="$label"
    crs:Pick="$pickStatus"
    xmpDM:pick="$pickStatus">
$tagsBag
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>""".trimIndent()
    }
}
