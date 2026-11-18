package org.EIQUI.RemoteRagdoll;

import com.github.stephengold.joltjni.Body;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import org.EIQUI.RemoteRagdoll.visual.EntityVisual;
import org.EIQUI.RemoteRagdoll.visual.PartVisual;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PhysicsVisualTest {
    private PhysicsBody body(PartVisual visual, int life) throws Exception {
        PhysicsWorld physics = mock(PhysicsWorld.class);
        World world = mock(World.class);
        when(world.getMinHeight()).thenReturn(-64);
        when(physics.getWorld()).thenReturn(world);
        Body nativeBody = mock(Body.class);
        when(nativeBody.getId()).thenReturn(42);
        when(visual.getTransformation()).thenReturn(new Transformation(
                new Vector3f(), new Quaternionf(), new Vector3f(1), new Quaternionf()));
        when(visual.isValid()).thenReturn(true);
        var constructor = PhysicsBody.class.getDeclaredConstructor(PhysicsWorld.class, Body.class,
                List.class, Vector3f.class, Vector3f.class, Vector3f.class,
                double.class, int.class, Quaternionf.class);
        constructor.setAccessible(true);
        return constructor.newInstance(physics, nativeBody, new ArrayList<>(List.of(visual)),
                new Vector3f(), new Vector3f(1), new Vector3f(), 0.0, life, new Quaternionf());
    }

    private RVec3 position() {
        RVec3 pos = mock(RVec3.class);
        when(pos.yy()).thenReturn(70.0);
        return pos;
    }

    private Quat rotation() {
        Quat rot = mock(Quat.class);
        when(rot.getW()).thenReturn(1f);
        return rot;
    }

    @Test void sleepingPacketsAreSilentButLifetimeStillExpires() throws Exception {
        PartVisual visual = mock(PartVisual.class);
        when(visual.isPacket()).thenReturn(true);
        PhysicsBody body = body(visual, 2);
        body.onPhysicsTick(position(), rotation(), false);
        verify(visual, never()).setTransform(any(), any(), any(), any());
        verify(visual, never()).moveTo(any());
        assertFalse(body.isDead());
        assertTrue(body.getDisplays().isEmpty());
        body.onPhysicsTick(position(), rotation(), false);
        assertTrue(body.isDead());
    }

    @Test void legacyRendererStillUpdatesSleepingBodies() throws Exception {
        PartVisual visual = mock(PartVisual.class);
        PhysicsBody body = body(visual, 0);
        body.onPhysicsTick(position(), rotation(), false);
        verify(visual).setTransform(any(), any(), any(), any());
        verify(visual).moveTo(any());
    }

    @Test void entityAdapterPreservesBukkitCallsAndInterpolationOrder() {
        Display display = mock(Display.class);
        EntityVisual visual = new EntityVisual(display);
        Location at = new Location(null, 1, 70, 2);
        Transformation transform = new Transformation(new Vector3f(0, -7168, 0),
                new Quaternionf().rotateY(0.7f), new Vector3f(0.5f), new Quaternionf().rotateX(1.2f));
        visual.configureInterpolation();
        visual.setTransform(transform.getTranslation(), transform.getLeftRotation(),
                transform.getScale(), transform.getRightRotation());
        visual.moveTo(at);
        var ordered = inOrder(display);
        ordered.verify(display).setTeleportDuration(1);
        ordered.verify(display).setInterpolationDelay(0);
        ordered.verify(display).setInterpolationDuration(1);
        ordered.verify(display).setTransformation(eq(transform));
        ordered.verify(display).teleport(at);
        assertEquals(List.of(display), visual.getDisplays());
    }
}
