package com.sanad.agent.model

data class DeliveryAppConfig(
    val packageName: String,
    val appName: String,
    val appNameArabic: String,
    val orderIdPatterns: List<Regex>,
    val etaPatterns: List<Regex>,
    val statusPatterns: Map<String, List<Regex>>,
    val chatActivityClass: String? = null
)

object DeliveryApps {
    
    val HUNGERSTATION = DeliveryAppConfig(
        packageName = "com.hungerstation.android",
        appName = "Hungerstation",
        appNameArabic = "هنقرستيشن",
        orderIdPatterns = listOf(
            Regex("""رقم الطلب[:\s]*#?(\d+)"""),
            Regex("""Order[:\s]*#?(\d+)"""),
            Regex("""#(\d{6,})""")
        ),
        etaPatterns = listOf(
            Regex("""(\d{1,2}):(\d{2})\s*(ص|م|AM|PM)?"""),
            Regex("""خلال\s*(\d+)\s*دقيقة"""),
            Regex("""(\d+)\s*min""")
        ),
        statusPatterns = mapOf(
            "preparing" to listOf(
                Regex("""جاري التحضير"""),
                Regex("""Preparing""")
            ),
            "on_the_way" to listOf(
                Regex("""في الطريق"""),
                Regex("""On the way"""),
                Regex("""المندوب في الطريق""")
            ),
            "delivered" to listOf(
                Regex("""تم التوصيل"""),
                Regex("""Delivered""")
            )
        ),
        chatActivityClass = "com.hungerstation.android.ui.chat.ChatActivity"
    )
    
    val JAHEZ = DeliveryAppConfig(
        packageName = "com.jahez",
        appName = "Jahez",
        appNameArabic = "جاهز",
        orderIdPatterns = listOf(
            Regex("""رقم الطلب[:\s]*#?(\d+)"""),
            Regex("""Order ID[:\s]*#?(\d+)"""),
            Regex("""#(\d{5,})""")
        ),
        etaPatterns = listOf(
            Regex("""(\d{1,2}):(\d{2})\s*(ص|م|AM|PM)?"""),
            Regex("""(\d+)\s*دقيقة"""),
            Regex("""(\d+)\s*min""")
        ),
        statusPatterns = mapOf(
            "preparing" to listOf(
                Regex("""جاري التحضير"""),
                Regex("""قيد التحضير""")
            ),
            "on_the_way" to listOf(
                Regex("""في الطريق"""),
                Regex("""جاري التوصيل""")
            ),
            "delivered" to listOf(
                Regex("""تم التوصيل""")
            )
        ),
        chatActivityClass = "com.jahez.chat.ChatActivity"
    )
    
    val TOYOU = DeliveryAppConfig(
        packageName = "com.toyou.customer",
        appName = "ToYou",
        appNameArabic = "تويو",
        orderIdPatterns = listOf(
            Regex("""رقم الطلب[:\s]*#?(\d+)"""),
            Regex("""#(\d{5,})""")
        ),
        etaPatterns = listOf(
            Regex("""(\d{1,2}):(\d{2})"""),
            Regex("""(\d+)\s*دقيقة""")
        ),
        statusPatterns = mapOf(
            "preparing" to listOf(Regex("""جاري التحضير""")),
            "on_the_way" to listOf(Regex("""في الطريق""")),
            "delivered" to listOf(Regex("""تم التوصيل"""))
        )
    )

    val MRSOOL = DeliveryAppConfig(
        packageName = "com.mrsool.customer",
        appName = "Mrsool",
        appNameArabic = "مرسول",
        orderIdPatterns = listOf(
            Regex("""رقم الطلب[:\s]*#?(\d+)"""),
            Regex("""#(\d{5,})""")
        ),
        etaPatterns = listOf(
            Regex("""(\d{1,2}):(\d{2})"""),
            Regex("""(\d+)\s*دقيقة""")
        ),
        statusPatterns = mapOf(
            "preparing" to listOf(Regex("""جاري التحضير""")),
            "on_the_way" to listOf(Regex("""في الطريق""")),
            "delivered" to listOf(Regex("""تم التوصيل"""))
        )
    )
    
    val CAREEM = DeliveryAppConfig(
        packageName = "com.careem.acma",
        appName = "Careem",
        appNameArabic = "كريم",
        orderIdPatterns = listOf(
            Regex("""Order[:\s]*#?([A-Z0-9]+)"""),
            Regex("""#([A-Z0-9]{6,})""")
        ),
        etaPatterns = listOf(
            Regex("""(\d{1,2}):(\d{2})\s*(AM|PM)?"""),
            Regex("""(\d+)\s*min""")
        ),
        statusPatterns = mapOf(
            "preparing" to listOf(Regex("""Preparing""")),
            "on_the_way" to listOf(Regex("""On the way"""), Regex("""Arriving""")),
            "delivered" to listOf(Regex("""Delivered"""))
        )
    )
    
    val ALL_APPS = listOf(HUNGERSTATION, JAHEZ, TOYOU, MRSOOL, CAREEM)
    
    fun getByPackageName(packageName: String): DeliveryAppConfig? {
        return ALL_APPS.find { it.packageName == packageName }
    }
    
    fun getSupportedPackageNames(): List<String> {
        return ALL_APPS.map { it.packageName }
    }
}
