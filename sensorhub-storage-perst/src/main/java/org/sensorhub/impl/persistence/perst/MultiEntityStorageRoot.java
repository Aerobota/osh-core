/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.persistence.perst;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import net.opengis.gml.v32.AbstractFeature;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import org.garret.perst.Storage;
import org.sensorhub.api.persistence.DataKey;
import org.sensorhub.api.persistence.IDataFilter;
import org.sensorhub.api.persistence.IDataRecord;
import org.sensorhub.api.persistence.IFoiFilter;
import org.sensorhub.api.persistence.IMultiSourceStorage;
import org.sensorhub.api.persistence.IObsStorage;
import org.sensorhub.api.persistence.IRecordInfo;
import org.sensorhub.impl.persistence.perst.TimeSeriesImpl.DBRecord;
import org.vast.util.Bbox;


/**
 * <p>
 * PERST implementation of an observation storage that can be fed by multiple
 * producers
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since May 8, 2015
 */
class MultiEntityStorageRoot extends ObsStorageRoot implements IObsStorage, IMultiSourceStorage<IObsStorage>
{
    Map<String, ObsStorageRoot> obsStores;
    
    
    /* to delegate iteration to each producer data store in a list, sequentially */
    abstract class MultiProducerIterator<ObjectType> implements Iterator<ObjectType>
    {
        Iterator<String> producerIt;
        Iterator<ObjectType> subIt;
        ObjectType nextObj;
        
        MultiProducerIterator(Iterator<String> producerIt)
        {
            this.producerIt = producerIt;
            next();
        }
        
        public final boolean hasNext()
        {
            return nextObj != null;
        }

        public final ObjectType next()
        {
            ObjectType obj = nextObj;
            
            while (subIt == null || !subIt.hasNext())
            {
                if (producerIt.hasNext())
                    subIt = getSubIterator(producerIt.next());
                else
                {
                    subIt = null;
                    break;
                }
            }
            
            nextObj = (subIt == null) ? null : subIt.next();
            return obj;
        }
        
        protected abstract Iterator<ObjectType> getSubIterator(String producerID);
    
        public final void remove()
        {
        }
    }
    
    
    /* to iterate through a list of producers records in parallel while sorting by time */
    abstract class MultiProducerTimeSortIterator<ObjectType> implements Iterator<ObjectType>
    {
        int numProducers;
        Iterator<DBRecord>[] iterators;
        DBRecord[] nextRecords;
        DBRecord nextRecord;
        
        MultiProducerTimeSortIterator(Collection<String> producerIDs)
        {
            this.numProducers = producerIDs.size();
            this.iterators = new Iterator[numProducers];
            this.nextRecords = new DBRecord[numProducers];
            
            // get first matching record for each producer
            int i = 0;
            for (String producerID: producerIDs)
            {
                Iterator<DBRecord> it = getSubIterator(producerID);
                iterators[i] = it;
                if (it.hasNext())
                    nextRecords[i] = it.next();
                i++;
            }
            
            // call it once to init things properly
            nextRecord();
        }
        
        public final boolean hasNext()
        {
            return nextRecord != null;
        }

        public final DBRecord nextRecord()
        {
            DBRecord rec = nextRecord;
            
            int minTimeIndex = -1;
            double minTime = Double.POSITIVE_INFINITY;
            
            // find record with earliest time stamp among producers
            for (int i = 0; i < numProducers; i++)
            {
                DBRecord candidateRec = nextRecords[i];
                if (candidateRec == null)
                    continue;
                
                double nextTimeStamp = candidateRec.key.timeStamp;
                if (nextTimeStamp < minTime)
                {
                    minTime = nextTimeStamp;
                    minTimeIndex = i;
                }
            }
            
            // if a record was found, prepare for next iteration by fetching the next
            // record on the corresponding iterator. Keep all the other ones
            if (minTimeIndex >= 0)
            {
                nextRecord = nextRecords[minTimeIndex];
                Iterator<DBRecord> recIt = iterators[minTimeIndex];
                if (recIt.hasNext())
                    nextRecords[minTimeIndex] = recIt.next();
                else
                    nextRecords[minTimeIndex] = null;
            }
            else
                nextRecord = null;
            
            return rec;
        }
        
        public abstract ObjectType next();
        
        protected abstract Iterator<DBRecord> getSubIterator(String producerID);
    
        public final void remove()
        {
        }
    }
    
    
    // default constructor needed on Android JVM
    MultiEntityStorageRoot() {}
    
    
    public MultiEntityStorageRoot(Storage db)
    {
        super(db);
        obsStores = db.<String, ObsStorageRoot>createMap(String.class, 20);
    }
    
    
    protected final ObsStorageRoot getEntityStorage(String entityID)
    {
        ObsStorageRoot obsStore = obsStores.get(entityID);
        if (obsStore == null)
            throw new IllegalArgumentException("No data store for entity " + entityID);
        return obsStore;
    }
    
    
    @Override
    public Collection<String> getProducerIDs()
    {
        return obsStores.keySet();
    }
    
    
    @Override
    public IObsStorage getDataStore(String producerID)
    {
        return getEntityStorage(producerID);
    }
    
    
    @Override
    public IObsStorage addDataStore(String producerID)
    {
        ObsStorageRoot obsStore = obsStores.get(producerID);
        if (obsStore != null)
            return obsStore;
        
        obsStore = new ObsStorageRoot(getStorage());
        obsStores.put(producerID, obsStore);
        return obsStore;
    }


    @Override
    public void addRecordType(String name, DataComponent recordStructure, DataEncoding recommendedEncoding)
    {
        super.addRecordType(name, recordStructure, recommendedEncoding);
        
        // also add record type to all data stores
        for (ObsStorageRoot dataStore: obsStores.values())
            dataStore.addRecordType(name, recordStructure, recommendedEncoding);
    }


    @Override
    public Map<String, ? extends IRecordInfo> getRecordTypes()
    {
        // for now just return the ones from the first data store
        for (ObsStorageRoot dataStore: obsStores.values())
            return dataStore.getRecordTypes();
        
        return Collections.EMPTY_MAP;
    }


    @Override
    public int getNumRecords(String recordType)
    {
        int numRecords = 0;
        
        for (ObsStorageRoot dataStore: obsStores.values())
        {
            if (dataStore.getRecordTypes().containsKey(recordType))
                numRecords += dataStore.getNumRecords(recordType);
        }
        
        return numRecords;
    }


    @Override
    public double[] getRecordsTimeRange(String recordType)
    {
        double[] timeRange = new double[] {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        
        for (ObsStorageRoot dataStore: obsStores.values())
        {
            if (dataStore.getRecordTypes().containsKey(recordType))
            {
                double[] storeTimeRange = dataStore.getRecordsTimeRange(recordType);
                if (storeTimeRange[0] < timeRange[0])
                    timeRange[0] = storeTimeRange[0];
                if (storeTimeRange[1] > timeRange[1])
                    timeRange[1] = storeTimeRange[1];
            }
        }
        
        return timeRange;
    }


    @Override
    public DataBlock getDataBlock(DataKey key)
    {
        return getEntityStorage(key.producerID).getDataBlock(key);
    }


    @Override
    public Iterator<DataBlock> getDataBlockIterator(final IDataFilter filter)
    {
        // use producer list from filter or use all producers
        Collection<String> producerIDs = filter.getProducerIDs();
        if (producerIDs == null || producerIDs.isEmpty())
            producerIDs = this.getProducerIDs();
        
        return new MultiProducerTimeSortIterator<DataBlock>(producerIDs)
        {
            public DataBlock next()
            {
                return nextRecord().value;
            }

            protected Iterator<DBRecord> getSubIterator(String producerID)
            {
                return (Iterator<DBRecord>)getEntityStorage(producerID).getRecordIterator(filter);
            }
        };
    }


    @Override
    public Iterator<? extends IDataRecord> getRecordIterator(final IDataFilter filter)
    {
        // use producer list from filter or use all producers
        Collection<String> producerIDs = filter.getProducerIDs();
        if (producerIDs == null || producerIDs.isEmpty())
            producerIDs = this.getProducerIDs();
        
        return new MultiProducerTimeSortIterator<IDataRecord>(producerIDs)
        {
            public IDataRecord next()
            {
                return nextRecord();
            }

            protected Iterator<DBRecord> getSubIterator(String producerID)
            {
                return (Iterator<DBRecord>)getEntityStorage(producerID).getRecordIterator(filter);
            }
        };
    }


    @Override
    public int getNumMatchingRecords(IDataFilter filter)
    {
        int numRecords = 0;
        
        for (String producerID: filter.getProducerIDs())
            numRecords += getEntityStorage(producerID).getNumMatchingRecords(filter);
        
        return numRecords;
    }


    @Override
    public void storeRecord(DataKey key, DataBlock data)
    {
        getEntityStorage(key.producerID).storeRecord(key, data);
    }


    @Override
    public void updateRecord(DataKey key, DataBlock data)
    {
        getEntityStorage(key.producerID).updateRecord(key, data);
    }


    @Override
    public void removeRecord(DataKey key)
    {
        getEntityStorage(key.producerID).removeRecord(key);
    }


    @Override
    public int removeRecords(IDataFilter filter)
    {
        // use producer list from filter or use all producers
        Collection<String> producerIDs = filter.getProducerIDs();
        if (producerIDs == null || producerIDs.isEmpty())
            producerIDs = this.getProducerIDs();
        
        int numDeleted = 0;        
        for (String producerID: producerIDs)
            numDeleted += getEntityStorage(producerID).removeRecords(filter);
        
        return numDeleted;
    }


    @Override
    public int getNumFois(IFoiFilter filter)
    {
        // use producer list from filter or use all producers
        Collection<String> producerIDs = filter.getProducerIDs();
        if (producerIDs == null || producerIDs.isEmpty())
            producerIDs = this.getProducerIDs();
        
        int numFois = 0;
        for (String producerID: producerIDs)
            numFois += getEntityStorage(producerID).getNumFois(filter);
        
        return numFois;
    }


    @Override
    public Bbox getFoisSpatialExtent()
    {
        Bbox bbox = new Bbox();
        for (String producerID: getProducerIDs())
            bbox.add(getEntityStorage(producerID).getFoisSpatialExtent());
        
        return bbox;
    }


    @Override
    public Iterator<String> getFoiIDs(final IFoiFilter filter)
    {
        // use producer list from filter or use all producers
        Collection<String> producerIDs = filter.getProducerIDs();
        if (producerIDs == null || producerIDs.isEmpty())
            producerIDs = this.getProducerIDs();
        
        return new MultiProducerIterator<String>(producerIDs.iterator())
        {
            protected Iterator<String> getSubIterator(String producerID)
            {
                return getEntityStorage(producerID).getFoiIDs(filter);
            }
        };
    }


    @Override
    public Iterator<AbstractFeature> getFois(final IFoiFilter filter)
    {
        // use producer list from filter or use all producers
        Collection<String> producerIDs = filter.getProducerIDs();
        if (producerIDs == null || producerIDs.isEmpty())
            producerIDs = this.getProducerIDs();
        
        return new MultiProducerIterator<AbstractFeature>(producerIDs.iterator())
        {
            protected Iterator<AbstractFeature> getSubIterator(String producerID)
            {
                return getEntityStorage(producerID).getFois(filter);
            }
        };
    }


    @Override
    public void storeFoi(String producerID, AbstractFeature foi)
    {
        getEntityStorage(producerID).storeFoi(producerID, foi);
    }
    
}
