/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.sensor;

import java.net.URL;
import java.util.List;
import org.sensorhub.api.config.DisplayInfo;
import org.sensorhub.api.module.ModuleConfig;


/**
 * <p>
 * Configuration options for sensors/actuators
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since Nov 5, 2010
 */
public class SensorConfig extends ModuleConfig
{
    
    /**
     * URL of SensorML description of the sensor
     */
    @DisplayInfo(label="SensorML", desc="URL of SensorML file")
    public String sensorML;
    
    
    /**
     * Automatically activate sensor when plugged in
     */
    public boolean autoActivate = true;
    
    
    /**
     * Enables/disables maintenance of SensorML history
     */
    public boolean enableHistory = true;
    
    
    /**
     * Allows hiding some of the sensor interfaces
     */
    public String[] hiddenIO;
    
        
    /**
     * Driver configuration groups (potentially one for each layer of a protocol stack)
     * Can be included in SensorML v2.0
     */
    public List<SensorDriverConfig> driverConfigs;
    
    
    /**
     * Gets the URL of the SensorML template.<br/>
     * If the {@link #sensorML} field is not a URL, it is interpreted as a
     * relative path relative to the config class.
     * @return URL of template SensorML description
     */
    public String getSensorDescriptionURL()
    {
        if (sensorML == null)
            return null;
        
        if (sensorML.contains(":"))
            return sensorML;
        
        // else try to get java resource
        URL resourceUrl = getClass().getResource(sensorML);
        return (resourceUrl == null) ? null : resourceUrl.toString();
    }
}
