package nl.falcop.betterweiview.icap

/** Fixed facts about the ear cam (docs/icap-protocol.md, "Transport"). */
object Camera {
    const val HOST = "192.168.1.1"
    const val PORT = 80
    const val USER = "admin"
    const val PASSWORD = ""

    /** CGI endpoint that answers `firmware_ver='…'; voudp=0;`. */
    const val PROPERTIES_URL = "http://$HOST/get_properties.cgi?user=$USER&pwd=$PASSWORD"
}
