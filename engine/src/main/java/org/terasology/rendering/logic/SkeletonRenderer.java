/*
 * Copyright 2014 MovingBlocks
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

package org.terasology.rendering.logic;

import com.google.common.collect.Maps;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.config.Config;
import org.terasology.entitySystem.entity.EntityManager;
import org.terasology.entitySystem.entity.EntityRef;
import org.terasology.entitySystem.entity.lifecycleEvents.OnActivatedComponent;
import org.terasology.entitySystem.event.ReceiveEvent;
import org.terasology.entitySystem.systems.BaseComponentSystem;
import org.terasology.entitySystem.systems.RegisterMode;
import org.terasology.entitySystem.systems.RegisterSystem;
import org.terasology.entitySystem.systems.RenderSystem;
import org.terasology.entitySystem.systems.UpdateSubscriberSystem;
import org.terasology.logic.location.Location;
import org.terasology.logic.location.LocationComponent;
import org.terasology.math.AABB;
import org.terasology.math.JomlUtil;
import org.terasology.math.MatrixUtils;
import org.terasology.math.geom.BaseQuat4f;
import org.terasology.math.geom.BaseVector3f;
import org.terasology.math.geom.Matrix4f;
import org.terasology.math.geom.Quat4f;
import org.terasology.math.geom.Vector3f;
import org.terasology.registry.In;
import org.terasology.rendering.assets.animation.MeshAnimation;
import org.terasology.rendering.assets.animation.MeshAnimationFrame;
import org.terasology.rendering.assets.material.Material;
import org.terasology.rendering.assets.skeletalmesh.Bone;
import org.terasology.rendering.opengl.OpenGLSkeletalMesh;
import org.terasology.rendering.world.WorldRenderer;
import org.terasology.utilities.Assets;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.glBegin;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glEnd;
import static org.lwjgl.opengl.GL11.glPopMatrix;
import static org.lwjgl.opengl.GL11.glPushMatrix;
import static org.lwjgl.opengl.GL11.glVertex3f;

/**
 */
@RegisterSystem(RegisterMode.CLIENT)
public class SkeletonRenderer extends BaseComponentSystem implements RenderSystem, UpdateSubscriberSystem {

    private static final Logger logger = LoggerFactory.getLogger(SkeletonRenderer.class);

    @In
    private EntityManager entityManager;

    @In
    private WorldRenderer worldRenderer;

    @In
    private Config config;

    private Random random = new Random();

    @ReceiveEvent(components = {SkeletalMeshComponent.class, LocationComponent.class})
    public void newSkeleton(OnActivatedComponent event, EntityRef entity) {
        SkeletalMeshComponent skeleton = entity.getComponent(SkeletalMeshComponent.class);
        if (skeleton.mesh == null) {
            return;
        }

        if (skeleton.boneEntities == null) {
            skeleton.boneEntities = Maps.newHashMap();
            for (Bone bone : skeleton.mesh.getBones()) {
                LocationComponent loc = new LocationComponent();
                EntityRef boneEntity = entityManager.create(loc);
                skeleton.boneEntities.put(bone.getName(), boneEntity);
            }
            for (Bone bone : skeleton.mesh.getBones()) {
                EntityRef boneEntity = skeleton.boneEntities.get(bone.getName());
                EntityRef parent = (bone.getParent() != null) ? skeleton.boneEntities.get(bone.getParent().getName()) : entity;
                Location.attachChild(parent, boneEntity);
            }
            for (Bone bone : skeleton.mesh.getBones()) {
                EntityRef boneEntity = skeleton.boneEntities.get(bone.getName());
                LocationComponent loc = boneEntity.getComponent(LocationComponent.class);
                loc.setLocalPosition(bone.getLocalPosition());
                loc.setLocalRotation(bone.getLocalRotation());
                loc.setLocalScale(bone.getLocalScale().x);
                boneEntity.saveComponent(loc);
                if (bone.getParent() == null) {
                    skeleton.rootBone = boneEntity;
                }
            }
            entity.saveComponent(skeleton);
        }
    }

    @Override
    public void update(float delta) {
        for (EntityRef entity : entityManager.getEntitiesWith(SkeletalMeshComponent.class, LocationComponent.class)) {
            updateSkeletalMeshOfEntity(entity, delta);
        }
    }

    private void updateSkeletalMeshOfEntity(EntityRef entity, float delta) {
        SkeletalMeshComponent skeletalMeshComp = entity.getComponent(SkeletalMeshComponent.class);

        if (skeletalMeshComp.animation == null && skeletalMeshComp.animationPool != null) {
            skeletalMeshComp.animation = randomAnimationData(skeletalMeshComp, random);
        }

        if (skeletalMeshComp.animation == null) {
            return;
        }

        if (skeletalMeshComp.animation.getFrameCount() < 1) {
            return;
        }
        skeletalMeshComp.animationTime += delta * skeletalMeshComp.animationRate;
        float animationDuration = getDurationOfAnimation(skeletalMeshComp);
        while (skeletalMeshComp.animationTime >= animationDuration) {
            MeshAnimation newAnimation;
            if (!skeletalMeshComp.loop) {
                newAnimation = null;
            } else if (skeletalMeshComp.animationPool != null && !skeletalMeshComp.animationPool.isEmpty()){
                newAnimation = randomAnimationData(skeletalMeshComp, random);
            } else {
                newAnimation = skeletalMeshComp.animation;
            }

            if (newAnimation == null) {
                MeshAnimation finishedAnimation = skeletalMeshComp.animation;
                skeletalMeshComp.animationTime = animationDuration;
                MeshAnimationFrame frame = skeletalMeshComp.animation.getFrame(skeletalMeshComp.animation.getFrameCount() - 1);
                updateSkeleton(skeletalMeshComp, frame, frame, 1.0f);
                // Set animation to null so that AnimEndEvent fires only once
                skeletalMeshComp.animation = null;
                entity.saveComponent(skeletalMeshComp);
                entity.send(new AnimEndEvent(finishedAnimation));
                return;
            }
            skeletalMeshComp.animationTime -= animationDuration;
            if (skeletalMeshComp.animationTime < 0) {
                // In case the float calculation wasn't exact:
                skeletalMeshComp.animationTime = 0;
            }
            skeletalMeshComp.animation = newAnimation;
            animationDuration = getDurationOfAnimation(skeletalMeshComp);
        }
        float framePos = skeletalMeshComp.animationTime / skeletalMeshComp.animation.getTimePerFrame();
        int frameAId = (int) framePos;
        int frameBId = frameAId + 1;
        if (frameBId >= skeletalMeshComp.animation.getFrameCount()) {
            // In case the float calcuation wasn't exact:
            frameBId = skeletalMeshComp.animation.getFrameCount() - 1;
        }
        MeshAnimationFrame frameA = skeletalMeshComp.animation.getFrame(frameAId);
        MeshAnimationFrame frameB = skeletalMeshComp.animation.getFrame(frameBId);
        updateSkeleton(skeletalMeshComp, frameA, frameB, framePos - frameAId);
        entity.saveComponent(skeletalMeshComp);
    }


    private float getDurationOfAnimation(SkeletalMeshComponent skeletalMeshComp) {
        return skeletalMeshComp.animation.getTimePerFrame() * (skeletalMeshComp.animation.getFrameCount() - 1);
    }


    private static MeshAnimation randomAnimationData(SkeletalMeshComponent skeletalMeshComp, Random random) {
        List<MeshAnimation> animationPool = skeletalMeshComp.animationPool;
        if (animationPool == null) {
            return null;
        }
        if (animationPool.isEmpty()) {
            return null;
        }
        return animationPool.get(random.nextInt(animationPool.size()));
    }

    private void updateSkeleton(SkeletalMeshComponent skeletalMeshComp, MeshAnimationFrame frameA, MeshAnimationFrame frameB, float interpolationVal) {
        for (int i = 0; i < skeletalMeshComp.animation.getBoneCount(); ++i) {
            String boneName = skeletalMeshComp.animation.getBoneName(i);
            Bone bone = skeletalMeshComp.mesh.getBone(boneName);
            EntityRef boneEntity = skeletalMeshComp.boneEntities.get(boneName);
            if (boneEntity == null || bone == null) {
                continue;
            }
            LocationComponent boneLoc = boneEntity.getComponent(LocationComponent.class);
            if (boneLoc != null) {
                Vector3f newPos = BaseVector3f.lerp(frameA.getPosition(i), frameB.getPosition(i), interpolationVal);
                boneLoc.setLocalPosition(newPos);
                Quat4f newRot =  BaseQuat4f.interpolate(frameA.getRotation(i), frameB.getRotation(i), interpolationVal);
                newRot.normalize();
                boneLoc.setLocalRotation(newRot);
                boneLoc.setLocalScale(BaseVector3f.lerp(frameA.getBoneScale(i), frameB.getBoneScale(i), interpolationVal).x);
                boneEntity.saveComponent(boneLoc);
            }
        }
    }

    @Override
    public void renderOpaque() {
        Vector3f cameraPosition = JomlUtil.from(worldRenderer.getActiveCamera().getPosition());

        Quat4f worldRot = new Quat4f();
        Vector3f worldPos = new Vector3f();
        Quat4f inverseWorldRot = new Quat4f();

        FloatBuffer tempMatrixBuffer44 = BufferUtils.createFloatBuffer(16);
        FloatBuffer tempMatrixBuffer33 = BufferUtils.createFloatBuffer(12);

        for (EntityRef entity : entityManager.getEntitiesWith(SkeletalMeshComponent.class, LocationComponent.class)) {

            SkeletalMeshComponent skeletalMesh = entity.getComponent(SkeletalMeshComponent.class);
            if (skeletalMesh.mesh == null || skeletalMesh.material == null || skeletalMesh.boneEntities == null || !skeletalMesh.material.isRenderable()) {
                continue;
            }
            AABB aabb;
            MeshAnimation animation = skeletalMesh.animation;
            if (animation != null) {
                aabb = animation.getAabb();
            } else {
                aabb = skeletalMesh.mesh.getStaticAabb();
            }
            LocationComponent location = entity.getComponent(LocationComponent.class);
            location.getWorldRotation(worldRot);
            inverseWorldRot.inverse(worldRot);
            location.getWorldPosition(worldPos);
            float worldScale = location.getWorldScale();

            aabb = aabb.transform(worldRot, worldPos, worldScale);
            if (!worldRenderer.getActiveCamera().hasInSight(aabb)) {
                continue;
            }

            skeletalMesh.material.enable();
            skeletalMesh.material.setFloat("sunlight", 1.0f, true);
            skeletalMesh.material.setFloat("blockLight", 1.0f, true);
            skeletalMesh.material.setFloat3("colorOffset", skeletalMesh.color.rf(), skeletalMesh.color.gf(), skeletalMesh.color.bf(), true);

            skeletalMesh.material.setMatrix4("projectionMatrix", worldRenderer.getActiveCamera().getProjectionMatrix());
            skeletalMesh.material.bindTextures();


            Vector3f worldPositionCameraSpace = new Vector3f();
            worldPositionCameraSpace.sub(worldPos, cameraPosition);

            worldPos.y -= skeletalMesh.heightOffset;

            Matrix4f matrixCameraSpace = new Matrix4f(worldRot, worldPositionCameraSpace, worldScale);

            Matrix4f modelViewMatrix = MatrixUtils.calcModelViewMatrix(worldRenderer.getActiveCamera().getViewMatrix(), matrixCameraSpace);
            MatrixUtils.matrixToFloatBuffer(modelViewMatrix, tempMatrixBuffer44);

            skeletalMesh.material.setMatrix4("worldViewMatrix", tempMatrixBuffer44, true);

            MatrixUtils.matrixToFloatBuffer(MatrixUtils.calcNormalMatrix(modelViewMatrix), tempMatrixBuffer33);
            skeletalMesh.material.setMatrix3("normalMatrix", tempMatrixBuffer33, true);

            skeletalMesh.material.setFloat("sunlight", worldRenderer.getMainLightIntensityAt(worldPos), true);
            skeletalMesh.material.setFloat("blockLight", worldRenderer.getBlockLightIntensityAt(worldPos), true);

            Matrix4f[] boneTransforms = new Matrix4f[skeletalMesh.mesh.getBones().size()];
            for (Bone bone : skeletalMesh.mesh.getBones()) {
                EntityRef boneEntity = skeletalMesh.boneEntities.get(bone.getName());
                if (boneEntity == null) {
                    boneEntity = EntityRef.NULL;
                }
                LocationComponent boneLocation = boneEntity.getComponent(LocationComponent.class);
                if (boneLocation != null) {
                    Matrix4f boneTransform = new Matrix4f(Matrix4f.IDENTITY);
                    boneLocation.getRelativeTransform(boneTransform, entity);
                    boneTransform.mul(bone.getInverseBindMatrix());
                    boneTransforms[bone.getIndex()] = boneTransform;
                } else {
                    logger.warn("Unable to resolve bone \"{}\"", bone.getName());
                    boneTransforms[bone.getIndex()] = new Matrix4f(Matrix4f.IDENTITY);

                }
            }
            ((OpenGLSkeletalMesh) skeletalMesh.mesh).setScaleTranslate(skeletalMesh.scale, skeletalMesh.translate);
            ((OpenGLSkeletalMesh) skeletalMesh.mesh).render(Arrays.asList(boneTransforms));
        }
    }

    @Override
    public void renderAlphaBlend() {
    }

    @Override
    public void renderOverlay() {
        if (config.getRendering().getDebug().isRenderSkeletons()) {
            glDisable(GL_DEPTH_TEST);
            Vector3f cameraPosition = JomlUtil.from(worldRenderer.getActiveCamera().getPosition());
            Material material = Assets.getMaterial("engine:white").get();
            material.setFloat("sunlight", 1.0f, true);
            material.setFloat("blockLight", 1.0f, true);
            material.setMatrix4("projectionMatrix", worldRenderer.getActiveCamera().getProjectionMatrix());
            Vector3f worldPos = new Vector3f();


            FloatBuffer tempMatrixBuffer44 = BufferUtils.createFloatBuffer(16);
            FloatBuffer tempMatrixBuffer33 = BufferUtils.createFloatBuffer(12);

            for (EntityRef entity : entityManager.getEntitiesWith(SkeletalMeshComponent.class, LocationComponent.class)) {
                LocationComponent location = entity.getComponent(LocationComponent.class);
                SkeletalMeshComponent meshComp = entity.getComponent(SkeletalMeshComponent.class);
                if (meshComp.mesh == null) {
                    continue;
                }

                Vector3f worldPositionCameraSpace = new Vector3f();
                worldPositionCameraSpace.sub(worldPos, cameraPosition);

                Matrix4f matrixCameraSpace = new Matrix4f(new Quat4f(0, 0, 0, 1), worldPositionCameraSpace, 1);

                Matrix4f modelViewMatrix = MatrixUtils.calcModelViewMatrix(worldRenderer.getActiveCamera().getViewMatrix(), matrixCameraSpace);
                MatrixUtils.matrixToFloatBuffer(modelViewMatrix, tempMatrixBuffer44);

                material.setMatrix4("worldViewMatrix", tempMatrixBuffer44, true);

                MatrixUtils.matrixToFloatBuffer(MatrixUtils.calcNormalMatrix(modelViewMatrix), tempMatrixBuffer33);
                material.setMatrix3("normalMatrix", tempMatrixBuffer33, true);

                for (Bone bone : meshComp.mesh.getBones()) {
                    if (bone.getParentIndex() != -1) {
                        renderBone(meshComp.boneEntities.get(bone.getName()));
                    }
                }
            }
            glEnable(GL_DEPTH_TEST);
        }
    }

    @Override
    public void renderShadows() {
    }

    private void renderBoneOrientation(EntityRef boneEntity) {
        LocationComponent loc = boneEntity.getComponent(LocationComponent.class);
        if (loc == null) {
            return;
        }
        glPushMatrix();
        Vector3f worldPosA = loc.getWorldPosition();
        Quat4f worldRot = loc.getWorldRotation();
        Vector3f offset = new Vector3f(0, 0, 0.1f);
        worldRot.rotate(offset, offset);
        offset.add(worldPosA);

        glBegin(GL11.GL_LINES);
        glVertex3f(worldPosA.x, worldPosA.y, worldPosA.z);
        glVertex3f(offset.x, offset.y, offset.z);
        glEnd();

        loc.getChildren().forEach(this::renderBoneOrientation);
        glPopMatrix();
    }

    private void renderBone(EntityRef boneEntity) {
        LocationComponent loc = boneEntity.getComponent(LocationComponent.class);
        if (loc == null) {
            return;
        }
        LocationComponent parentLoc = loc.getParent().getComponent(LocationComponent.class);
        if (parentLoc != null) {
            Vector3f worldPosA = loc.getWorldPosition();
            Vector3f worldPosB = parentLoc.getWorldPosition();

            glBegin(GL11.GL_LINES);
            glVertex3f(worldPosA.x, worldPosA.y, worldPosA.z);
            glVertex3f(worldPosB.x, worldPosB.y, worldPosB.z);
            glEnd();
        }
    }
}
