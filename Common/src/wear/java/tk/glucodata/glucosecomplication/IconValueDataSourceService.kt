/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */
/*                                                                                   */
/*      Fri Oct 11 12:22:15 CEST 2024                                                 */


package tk.glucodata.glucosecomplication


import android.content.ComponentName
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.ComplicationType.*
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.MainActivity
import tk.glucodata.R
import java.lang.Math.min

class IconValueDataSourceService: SuspendingComplicationDataSourceService()  {
    /** The number, drawn with the app's palette rather than the legacy colours. */
    private fun valueBitmap(reading: GlucoseComplicationData.Reading): android.graphics.Bitmap =
        // ICON slots are tinted by the face, so the mask is drawn white (half
        // strength once stale); the text forms carry the value as text instead.
        ComplicationRenderer.valueBitmap(
            ICON_PX, reading.text, reading.value, reading.isMmol, ComplicationRenderer.iconTint(reading),
        )

    override fun onComplicationActivated( complicationInstanceId: Int, type: ComplicationType) {
        Log.d(LOG_ID, "onComplicationActivated(): $complicationInstanceId")
    }
    override fun onComplicationDeactivated(complicationInstanceId: Int) {
        Log.d(LOG_ID, "onComplicationDeactivated(): $complicationInstanceId")
    }
    override fun getPreviewData(type: ComplicationType): ComplicationData {
        val reading = GlucoseComplicationData.previewReading()
        val tapAction = GlucoseComplicationData.tapAction()
        return when (type) {
            MONOCHROMATIC_IMAGE -> {
                MonochromaticImageComplicationData.Builder(
                    MonochromaticImage.Builder(Icon.createWithBitmap(valueBitmap(reading))).build(),
                    contentDescription = PlainComplicationText.Builder(text = "Glucose Value").build(),
                )
                    .setTapAction(tapAction)
                    .build()
            }
            SHORT_TEXT -> GlucoseComplicationData.shortValueData(
                reading,
                "Glucose Value",
                tapAction,
            )
            LONG_TEXT -> GlucoseComplicationData.longTextData(
                reading,
                "Glucose Value",
                tapAction,
            )
            RANGED_VALUE -> GlucoseComplicationData.rangedValueData(
                reading,
                "Glucose Value",
                tapAction,
            )
            else -> GlucoseComplicationData.shortValueData(null, "Glucose Value", tapAction)
        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        Log.d(LOG_ID, "onComplicationRequest() id: ${request.complicationInstanceId}")
        val type=        request.complicationType
        val glucose = GlucoseComplicationData.currentReading()
    if(type== MONOCHROMATIC_IMAGE) {
            val bitmap= if(glucose==null){
                  Log.i(LOG_ID,"MonochromaticImage novalue")
                 ComplicationRenderer.noValueBitmap(ICON_PX, ComplicationRenderer.ICON_TINT, icon = true)
                 } else {
                     Log.i(LOG_ID,"MonochromaticImage ${glucose.text}")
                    valueBitmap(glucose)
                     }
             val image=Icon.createWithBitmap(bitmap)
             val complicationPendingIntent = GlucoseComplicationData.tapAction()
            return MonochromaticImageComplicationData.Builder(
                    MonochromaticImage.Builder(image).build(), contentDescription = PlainComplicationText.Builder("Glucose Value").build()).setTapAction(complicationPendingIntent).build()
            } 
    else if (type == SHORT_TEXT) {
            return GlucoseComplicationData.shortValueData(
                glucose,
                "Glucose Value",
                GlucoseComplicationData.tapAction(),
            )
            }
    else if (type == LONG_TEXT) {
            // Declared in the manifest, so a long slot offers this source; it has to answer.
            return GlucoseComplicationData.longTextData(
                glucose,
                "Glucose Value",
                GlucoseComplicationData.tapAction(),
            )
            }
    else if (type == RANGED_VALUE) {
            return GlucoseComplicationData.rangedValueData(
                glucose,
                "Glucose Value",
                GlucoseComplicationData.tapAction(),
            )
            }
    else {
                Log.w(LOG_ID, "Unexpected complication type ${request.complicationType}")
                return null
           }
    }

    companion object {
        private const val LOG_ID = "IconValueDataSourceService"
        private const val ICON_PX = 256
   private val complicationDataSourceUpdateRequester = ComplicationDataSourceUpdateRequester.create( context=tk.glucodata.Applic.app, complicationDataSourceComponent = ComponentName(tk.glucodata.Applic.app, IconValueDataSourceService::class.java))

        public fun update() {
            complicationDataSourceUpdateRequester.requestUpdateAll()
        }
    }
}
