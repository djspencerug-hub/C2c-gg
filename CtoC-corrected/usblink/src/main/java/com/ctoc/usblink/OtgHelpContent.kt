package com.ctoc.usblink

object OtgHelpContent {
    const val TITLE = "USB-C & OTG Connection Help"

    val BODY = """
        Why a direct USB-C to USB-C cable may fail:
        Dual-role USB-C ports on two phones can fail to negotiate USB Host vs. Accessory roles, or default to charging-only mode.

        Recommended OTG Adapter Setup:
        1. Plug a USB-C OTG host adapter (USB-C male to USB-A female) directly into the Redmi phone (Receiver). This forces the Redmi into USB Host mode.
        2. Connect a USB-A to USB-C data cable (not a charge-only cable) from the OTG adapter to the Samsung Galaxy Note 20 (Sender).
        3. On Xiaomi/Redmi (MIUI/HyperOS): Open Settings → Additional settings → OTG and turn it ON if the adapter is not detected automatically.
        4. On the Galaxy Note 20: Ensure USB mode is not locked to "Charging only" and accept both the USB Accessory and Screen Capture prompts.
        5. On the Redmi: Accept the USB Host permission dialog, or tap Reconnect if switching cables.
    """.trimIndent()
}
