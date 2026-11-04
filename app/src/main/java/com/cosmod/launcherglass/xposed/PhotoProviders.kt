package com.cosmod.launcherglass.xposed

/** Gallery branding differs between China, global Oplus and OnePlus builds. */
internal object PhotoProviders {
    private val galleries=setOf("com.coloros.gallery3d","com.oplus.gallery3d","com.oneplus.gallery")
    private val galleryCards=setOf(222220043,222220044)
    fun supports(packageName:String?,className:String?,cardType:Int?,card:Boolean):Boolean {
        if(packageName in galleries){
            val name=className.orEmpty()
            return cardType in galleryCards || name.contains("widget",true) || name.contains("photo",true) || name.contains("picture",true)
        }
        if(packageName==null)return card && cardType in galleryCards
        if(card)return false
        return when(packageName){
            "com.fibelatti.photowidget" -> className.orEmpty().contains("WidgetProvider")
            "com.google.android.apps.photos" -> className.orEmpty().contains("photo",true) || className.orEmpty().contains("widget",true)
            else -> false
        }
    }
}
