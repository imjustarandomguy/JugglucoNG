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
/*      Sun Mar 10 11:40:55 CET 2024                                                 */


package tk.glucodata;

import tk.glucodata.widget.GlucoseWidgetProvider;
import tk.glucodata.widget.GlucoseWidgets;
import tk.glucodata.widget.WidgetKind;

/**
 * The "Glucose" widget: the value, trend arrow and reading time. The class name
 * stays, since placed widgets are bound to it; the drawing is in
 * {@link GlucoseWidgets}, shared with the chart widget.
 */
public class GlucoseWidget extends GlucoseWidgetProvider {
    public GlucoseWidget() {
        super(WidgetKind.VALUE);
    }

    /** The loss alarm found no new reading in time: switch the widgets to their stale look. */
    public static void oldvalue(long time) {
        GlucoseWidgets.onReadingStale(Applic.app);
    }
}
