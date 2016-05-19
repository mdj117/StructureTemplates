/*
 * Copyright 2016 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.structureTemplates.internal.systems;

import org.terasology.entitySystem.entity.EntityBuilder;
import org.terasology.entitySystem.entity.EntityManager;
import org.terasology.entitySystem.entity.EntityRef;
import org.terasology.entitySystem.event.ReceiveEvent;
import org.terasology.entitySystem.systems.BaseComponentSystem;
import org.terasology.entitySystem.systems.RegisterMode;
import org.terasology.entitySystem.systems.RegisterSystem;
import org.terasology.logic.common.ActivateEvent;
import org.terasology.logic.inventory.InventoryManager;
import org.terasology.math.Region3i;
import org.terasology.math.Side;
import org.terasology.math.geom.Vector3f;
import org.terasology.math.geom.Vector3i;
import org.terasology.registry.In;
import org.terasology.structureTemplates.components.SpawnBlockRegionsComponent;
import org.terasology.structureTemplates.components.SpawnBlockRegionsComponent.RegionToFill;
import org.terasology.structureTemplates.components.container.Region;
import org.terasology.structureTemplates.internal.components.CreateStructureSpawnItemRequest;
import org.terasology.structureTemplates.internal.components.FrontDirectionComponent;
import org.terasology.structureTemplates.internal.events.CopyBlockRegionRequest;
import org.terasology.structureTemplates.internal.events.CopyBlockRegionResultEvent;
import org.terasology.structureTemplates.util.transform.HorizontalBlockRegionRotation;
import org.terasology.world.WorldProvider;
import org.terasology.world.block.Block;
import org.terasology.world.block.BlockComponent;
import org.terasology.world.block.BlockManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Handles the activation of the copyBlockRegionTool item.
 */
@RegisterSystem(RegisterMode.AUTHORITY)
public class StructureTemplateEditorServerSystem extends BaseComponentSystem {
    private static final Comparator<RegionToFill> REGION_BY_MIN_X_COMPARATOR = Comparator.comparing(r -> r.region.min.x());
    private static final Comparator<RegionToFill> REGION_BY_MIN_Y_COMPARATOR = Comparator.comparing(r -> r.region.min.y());
    private static final Comparator<RegionToFill> REGION_BY_MIN_Z_COMPARATOR = Comparator.comparing(r -> r.region.min.z());

    @In
    private WorldProvider worldProvider;

    @In
    private BlockManager blockManager;

    @In
    private InventoryManager inventoryManager;

    @In
    private EntityManager entityManager;


    @ReceiveEvent
    public void onActivate(ActivateEvent event, EntityRef entity,
                           StructureTemplateGeneratorComponent structureTemplateEditorComponent) {
        BlockComponent blockComponent = event.getTarget().getComponent(BlockComponent.class);
        if (blockComponent == null) {
            return;
        }
        EntityRef owner = entity.getOwner();
        Vector3i position = blockComponent.getPosition();


        Vector3f directionVector = event.getDirection();
        Side directionStructureIsIn = Side.inHorizontalDirection(directionVector.getX(), directionVector.getZ());
        Side frontDirectionOfStructure = directionStructureIsIn.reverse();


        Region3i unrotatedRegion = Region3i.createBounded(new Vector3i(-2, 0, 0), new Vector3i(2, 4, 6));


        HorizontalBlockRegionRotation rotation = HorizontalBlockRegionRotation.createRotationFromSideToSide(Side.FRONT,
                frontDirectionOfStructure);
        Region3i region = rotation.transformRegion(unrotatedRegion);


        EntityBuilder entityBuilder = entityManager.newBuilder("StructureTemplates:structureTemplateEditor");
        StructureTemplateEditorComponent editorComponent = entityBuilder.getComponent(StructureTemplateEditorComponent.class);
        editorComponent.editRegion = new Region();
        editorComponent.editRegion.min.set(region.min());
        editorComponent.editRegion.max.set(region.max());
        editorComponent.origin.set(position);
        entityBuilder.saveComponent(editorComponent);
        FrontDirectionComponent frontDirectionComponent = new FrontDirectionComponent();
        frontDirectionComponent.direction = frontDirectionOfStructure;
        entityBuilder.addOrSaveComponent(frontDirectionComponent);
        EntityRef editorItem = entityBuilder.build();

        inventoryManager.giveItem(owner, owner, editorItem);

    }


    @ReceiveEvent
    public void onCreateStructureSpawnItemRequest(CreateStructureSpawnItemRequest event, EntityRef entity,
                                                  StructureTemplateEditorComponent structureTemplateEditorComponent) {
        EntityBuilder entityBuilder = entityManager.newBuilder("StructureTemplates:structureSpawnItem");
        SpawnBlockRegionsComponent spawnBlockRegionsComponent = new SpawnBlockRegionsComponent();
        spawnBlockRegionsComponent.regionsToFill = createRegionsToFill(structureTemplateEditorComponent);

        FrontDirectionComponent templateFrontDirComp = entity.getComponent(FrontDirectionComponent.class);
        Side frontOfStructure = (templateFrontDirComp != null) ? templateFrontDirComp.direction : Side.FRONT;

        entityBuilder.addOrSaveComponent(spawnBlockRegionsComponent);
        FrontDirectionComponent frontDirectionComponent = new FrontDirectionComponent();
        frontDirectionComponent.direction = frontOfStructure;
        entityBuilder.addOrSaveComponent(frontDirectionComponent);
        EntityRef structureSpawnITem = entityBuilder.build();

        inventoryManager.giveItem(entity.getOwner(), EntityRef.NULL, structureSpawnITem);
    }

    @ReceiveEvent
    public void onCopyBlockRegionRequest(CopyBlockRegionRequest event, EntityRef entity, StructureTemplateEditorComponent structureTemplateEditorComponent) {
        List<RegionToFill> regionsToFill = createRegionsToFill(structureTemplateEditorComponent);
        String textToSend = formatAsString(regionsToFill);

        CopyBlockRegionResultEvent resultEvent = new CopyBlockRegionResultEvent(textToSend);
        entity.send(resultEvent);
    }

    private List<RegionToFill> createRegionsToFill(StructureTemplateEditorComponent structureTemplateEditorComponent) {
        Region3i absoluteRegion = Region3i.createBounded(structureTemplateEditorComponent.editRegion.min
                , structureTemplateEditorComponent.editRegion.max);
        absoluteRegion = absoluteRegion.move(structureTemplateEditorComponent.origin);
        Block airBlock = blockManager.getBlock("engine:air");
        List<RegionToFill> regionsToFill = new ArrayList<>();
        for (Vector3i absolutePosition : absoluteRegion) {
            Block block = worldProvider.getBlock(absolutePosition);
            if (block == airBlock) {
                /*
                 * We assume that air is there and does not need to be placed. This makes it possible
                 * to create structures for underwater on land and then have them be placed under water without air.
                 */
                continue;
            }
            RegionToFill regionToFill = new RegionToFill();
            Vector3i relativePosition = new Vector3i(absolutePosition);
            relativePosition.sub(structureTemplateEditorComponent.origin);
            regionToFill.region = new Region();
            regionToFill.region.min.set(relativePosition);
            regionToFill.region.max.set(relativePosition);
            regionToFill.blockType = block.getURI().toString();
            regionsToFill.add(regionToFill);
        }
        mergeRegionsByX(regionsToFill);
        mergeRegionsByY(regionsToFill);
        mergeRegionsByZ(regionsToFill);
        return regionsToFill;
    }


    static void mergeRegionsByX(List<RegionToFill> regions) {
        regions.sort(REGION_BY_MIN_Y_COMPARATOR.thenComparing(REGION_BY_MIN_Z_COMPARATOR).
                thenComparing(REGION_BY_MIN_X_COMPARATOR));
        List<RegionToFill> newList = new ArrayList<>();
        RegionToFill previous = null;
        for (RegionToFill r: regions) {
            boolean canMerge = previous != null && previous.region.max.x() == r.region.min.x() -1
                    && r.region.min.y() == previous.region.min.y() && r.region.max.y() == previous.region.max.y()
                    && r.region.min.z() == previous.region.min.z() && r.region.max.z() == previous.region.max.z()
                    && r.blockType.equals(previous.blockType);
            if (canMerge) {
                previous.region.max.setX(r.region.max.x());
            } else {
                newList.add(r);
                previous = r;
            }
        }
        regions.clear();
        regions.addAll(newList);
    }

    static void mergeRegionsByY(List<RegionToFill> regions) {
        regions.sort(REGION_BY_MIN_X_COMPARATOR.thenComparing(REGION_BY_MIN_Z_COMPARATOR).
                thenComparing(REGION_BY_MIN_Y_COMPARATOR));
        List<RegionToFill> newList = new ArrayList<>();
        RegionToFill previous = null;
        for (RegionToFill r: regions) {
            boolean canMerge = previous != null && previous.region.max.y() == r.region.min.y() -1
                    && r.region.min.x() == previous.region.min.x() && r.region.max.x() == previous.region.max.x()
                    && r.region.min.z() == previous.region.min.z() && r.region.max.z() == previous.region.max.z()
                    && r.blockType.equals(previous.blockType);
            if (canMerge) {
                previous.region.max.setY(r.region.max.y());
            } else {
                newList.add(r);
                previous = r;
            }
        }
        regions.clear();
        regions.addAll(newList);
    }

    static void mergeRegionsByZ(List<RegionToFill> regions) {
        regions.sort(REGION_BY_MIN_X_COMPARATOR.thenComparing(REGION_BY_MIN_Y_COMPARATOR).
                thenComparing(REGION_BY_MIN_Y_COMPARATOR));
        List<RegionToFill> newList = new ArrayList<>();
        RegionToFill previous = null;
        for (RegionToFill r: regions) {
            boolean canMerge = previous != null && previous.region.max.z() == r.region.min.z() -1
                    && r.region.min.x() == previous.region.min.x() && r.region.max.x() == previous.region.max.x()
                    && r.region.min.y() == previous.region.min.y() && r.region.max.y() == previous.region.max.y()
                    && r.blockType.equals(previous.blockType);
            if (canMerge) {
                previous.region.max.setZ(r.region.max.z());
            } else {
                newList.add(r);
                previous = r;
            }
        }
        regions.clear();
        regions.addAll(newList);
    }

    static String formatAsString(List<RegionToFill> regionsToFill) {
        StringBuilder sb = new StringBuilder();
        for (RegionToFill regionToFill: regionsToFill) {
            sb.append("            { \"blockType\": \"");
            sb.append(regionToFill.blockType);
            sb.append("\", \"region\": { \"min\": [");
            sb.append(regionToFill.region.min.x);
            sb.append(", ");
            sb.append(regionToFill.region.min.y);
            sb.append(", ");
            sb.append(regionToFill.region.min.z);
            sb.append("], \"max\": [");
            sb.append(regionToFill.region.max.x);
            sb.append(", ");
            sb.append(regionToFill.region.max.y);
            sb.append(", ");
            sb.append(regionToFill.region.max.z);
            sb.append("]}},\n");
        }
        return sb.toString();
    }

}
