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
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.ComplicationType.*
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.MainActivity
import tk.glucodata.R
import java.lang.Math.min

class IconArrowDataSourceService: SuspendingComplicationDataSourceService()  {
    /**
     * Arrow plus the reading's time, drawn by ComplicationRenderer so the angle
     * and colour match the app rather than the legacy geometry. A stale reading
     * has no arrow: its time is left, dimmed.
     */
    private fun arrowTimeBitmap(reading: GlucoseComplicationData.Reading): android.graphics.Bitmap =
        // Drawn as a mask: the watch face tints an ICON, so a range colour here
        // came out off-tone beside every other complication on the face.
        ComplicationRenderer.arrowWithTimeBitmap(
            ICON_PX, reading.rate, reading.timeMillis, ComplicationRenderer.iconTint(reading),
        )

    private val ICON_PX = 256

    override fun onComplicationActivated( complicationInstanceId: Int, type: ComplicationType) {
        Log.d(LOG_ID, "onComplicationActivated(): $complicationInstanceId")
    }
    override fun onComplicationDeactivated(complicationInstanceId: Int) {
        Log.d(LOG_ID, "onComplicationDeactivated(): $complicationInstanceId")
    }
    override fun getPreviewData(type: ComplicationType): ComplicationData {
      return MonochromaticImageComplicationData.Builder(
          MonochromaticImage.Builder(
              Icon.createWithBitmap(arrowTimeBitmap(GlucoseComplicationData.previewReading())),
          ).build(),
            contentDescription = PlainComplicationText.Builder(text = "Glucose Arrow").build() )
            .setTapAction(GlucoseComplicationData.tapAction())
            .build()
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        Log.d(LOG_ID, "onComplicationRequest() id: ${request.complicationInstanceId}")
        val type=        request.complicationType
    if(type== MONOCHROMATIC_IMAGE) {
            // The watch's one current reading, as every other complication shows it.
            val glucose = GlucoseComplicationData.currentReading()
            val bitmap= if(glucose==null){
                 Log.i(LOG_ID,"MonochromaticImage novalue")
                 ComplicationRenderer.noValueBitmap(ICON_PX, ComplicationRenderer.ICON_TINT, icon = true)
                 } else {
                     Log.i(LOG_ID,"MonochromaticImage rate: ${glucose.rate}")
                      arrowTimeBitmap(glucose)
                     }
             val image=Icon.createWithBitmap(bitmap)
             val complicationPendingIntent = GlucoseComplicationData.tapAction()
            return MonochromaticImageComplicationData.Builder(
                    MonochromaticImage.Builder(image).build(), contentDescription = PlainComplicationText.Builder("Glucose Arrow").build()).setTapAction(complicationPendingIntent).build()
            } 
    else {
                Log.w(LOG_ID, "Unexpected complication type ${request.complicationType}")
                return null
           }
    }

    companion object {
        private const val LOG_ID = "IconArrowDataSourceService"
   private val complicationDataSourceUpdateRequester = ComplicationDataSourceUpdateRequester.create( context=tk.glucodata.Applic.app, complicationDataSourceComponent = ComponentName(tk.glucodata.Applic.app, IconArrowDataSourceService::class.java))

        public fun update() {
            complicationDataSourceUpdateRequester.requestUpdateAll()
        }
    }
}
