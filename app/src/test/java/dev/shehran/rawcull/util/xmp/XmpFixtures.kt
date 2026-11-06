package dev.shehran.rawcull.util.xmp

/** Sidecar fixtures from `src/test/resources/xmp`. */
object XmpFixtures {
    const val LIGHTROOM = "lightroom_classic_develop.xmp"
    const val DARKTABLE = "darktable.xmp"
    const val BRIDGE_REJECT = "bridge_reject.xmp"
    const val ELEMENT_FORM = "element_form.xmp"
    const val MULTI_DESCRIPTION = "multi_description.xmp"
    const val LEGACY = "rawcull_v0_legacy.xmp"
    const val NO_XPACKET = "no_xpacket.xmp"
    const val MALFORMED = "malformed.xmp"

    val valid = listOf(LIGHTROOM, DARKTABLE, BRIDGE_REJECT, ELEMENT_FORM, MULTI_DESCRIPTION, LEGACY, NO_XPACKET)

    fun bytes(name: String): ByteArray =
        requireNotNull(XmpFixtures::class.java.getResourceAsStream("/xmp/$name")) { "Missing fixture $name" }
            .use { it.readBytes() }
}
