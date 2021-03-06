/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.map.impl.operation;

import com.hazelcast.internal.nearcache.impl.invalidation.MetaDataGenerator;
import com.hazelcast.map.impl.MapDataSerializerHook;
import com.hazelcast.map.impl.MapService;
import com.hazelcast.map.impl.MapServiceContext;
import com.hazelcast.map.impl.nearcache.MapNearCacheManager;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.IdentifiedDataSerializable;
import com.hazelcast.spi.Operation;
import com.hazelcast.spi.ReadonlyOperation;
import com.hazelcast.spi.partition.IPartitionService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.hazelcast.map.impl.MapDataSerializerHook.F_ID;
import static com.hazelcast.map.impl.MapDataSerializerHook.MAP_INVALIDATION_METADATA;
import static com.hazelcast.map.impl.MapDataSerializerHook.MAP_INVALIDATION_METADATA_RESPONSE;
import static com.hazelcast.util.CollectionUtil.isNotEmpty;
import static com.hazelcast.util.Preconditions.checkTrue;

public class MapGetInvalidationMetaDataOperation extends Operation implements IdentifiedDataSerializable, ReadonlyOperation {

    private List<String> mapNames;
    private MetaDataResponse response;

    public MapGetInvalidationMetaDataOperation() {
    }

    public MapGetInvalidationMetaDataOperation(List<String> mapNames) {
        checkTrue(isNotEmpty(mapNames), "mapNames cannot be null or empty");
        this.mapNames = mapNames;
    }

    @Override
    public String getServiceName() {
        return MapService.SERVICE_NAME;
    }

    @Override
    public void run() {
        List<Integer> ownedPartitions = getOwnedPartitions();

        response = new MetaDataResponse();
        response.partitionUuidList = getPartitionUuidList(ownedPartitions);
        response.namePartitionSequenceList = getNamePartitionSequenceList(ownedPartitions);
    }

    public static class MetaDataResponse implements IdentifiedDataSerializable {
        /**
         * Holds per map sequence of `partitionId + sequence` pairs like this:
         * name1 + partition1 + sequence1 + partition2 + sequence2 + ... + name2 + partition1 + sequence1 + ... + name3 + ...
         */
        private List<Object> namePartitionSequenceList;

        /**
         * Holds sequence of partition + uuid pairs like this:
         * partition1 + uuid1 + partition2 + uuid2 + ... + partitionN + uuidN
         */
        private List<Object> partitionUuidList;

        public List<Object> getNamePartitionSequenceList() {
            return namePartitionSequenceList;
        }

        public List<Object> getPartitionUuidList() {
            return partitionUuidList;
        }

        @Override
        public int getFactoryId() {
            return MapDataSerializerHook.F_ID;
        }

        @Override
        public int getId() {
            return MAP_INVALIDATION_METADATA_RESPONSE;
        }

        @Override
        public void writeData(ObjectDataOutput out) throws IOException {
            out.writeInt(namePartitionSequenceList.size());
            for (Object o : namePartitionSequenceList) {
                out.writeObject(o);
            }

            out.writeInt(partitionUuidList.size());
            for (Object o : partitionUuidList) {
                out.writeObject(o);
            }
        }

        @Override
        public void readData(ObjectDataInput in) throws IOException {
            int size1 = in.readInt();
            namePartitionSequenceList = new ArrayList(size1);
            for (int i = 0; i < size1; i++) {
                namePartitionSequenceList.add(in.readObject());
            }

            int size2 = in.readInt();
            partitionUuidList = new ArrayList(size2);
            for (int i = 0; i < size2; i++) {
                partitionUuidList.add(in.readObject());
            }
        }
    }

    private List<Object> getNamePartitionSequenceList(List<Integer> ownedPartitionIds) {
        MetaDataGenerator metaDataGenerator = getPartitionMetaDataGenerator();
        List<Object> sequences = new ArrayList(ownedPartitionIds.size() * 2);

        for (String name : mapNames) {
            int foundFirstSequence = 0;
            for (Integer partitionId : ownedPartitionIds) {
                long partitionSequence = metaDataGenerator.currentSequence(name, partitionId);
                if (partitionSequence != 0) {
                    if (foundFirstSequence == 0) {
                        sequences.add(name);
                        foundFirstSequence++;
                    }
                    sequences.add(partitionId);
                    sequences.add(partitionSequence);
                }
            }
        }

        return sequences;
    }

    private List<Object> getPartitionUuidList(List<Integer> ownedPartitionIds) {
        MetaDataGenerator metaDataGenerator = getPartitionMetaDataGenerator();

        List<Object> partitionUuids = new ArrayList(ownedPartitionIds.size() * 2);
        for (Integer partitionId : ownedPartitionIds) {
            UUID uuid = metaDataGenerator.getUuidOrNull(partitionId);
            if (uuid != null) {
                partitionUuids.add(partitionId);
                partitionUuids.add(uuid.getMostSignificantBits());
                partitionUuids.add(uuid.getLeastSignificantBits());
            }
        }

        return partitionUuids;
    }

    private List<Integer> getOwnedPartitions() {
        List<Integer> ownedPartitions = new ArrayList<Integer>();
        IPartitionService partitionService = getNodeEngine().getPartitionService();
        for (int i = 0; i < partitionService.getPartitionCount(); i++) {
            if (partitionService.isPartitionOwner(i)) {
                ownedPartitions.add(i);
            }
        }
        return ownedPartitions;
    }

    private MetaDataGenerator getPartitionMetaDataGenerator() {
        MapService mapService = getService();
        MapServiceContext mapServiceContext = mapService.getMapServiceContext();
        MapNearCacheManager nearCacheManager = mapServiceContext.getMapNearCacheManager();
        return nearCacheManager.getInvalidator().getMetaDataGenerator();
    }

    @Override
    public Object getResponse() {
        return response;
    }

    @Override
    public void writeInternal(ObjectDataOutput out) throws IOException {
        super.writeInternal(out);

        out.writeInt(mapNames.size());

        for (String mapName : mapNames) {
            out.writeUTF(mapName);
        }
    }

    @Override
    public void readInternal(ObjectDataInput in) throws IOException {
        super.readInternal(in);

        int size = in.readInt();

        List<String> mapNames = new ArrayList<String>(size);
        for (int i = 0; i < size; i++) {
            mapNames.add(in.readUTF());
        }

        this.mapNames = mapNames;
    }

    @Override
    public int getFactoryId() {
        return F_ID;
    }

    @Override
    public int getId() {
        return MAP_INVALIDATION_METADATA;
    }

}
