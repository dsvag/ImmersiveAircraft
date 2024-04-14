package immersive_aircraft.entity;

import immersive_aircraft.Sounds;
import immersive_aircraft.cobalt.network.NetworkHandler;
import immersive_aircraft.config.Config;
import immersive_aircraft.entity.misc.VehicleInventoryDescription;
import immersive_aircraft.item.upgrade.VehicleStat;
import immersive_aircraft.network.c2s.EnginePowerMessage;
import immersive_aircraft.resources.bbmodel.BBAnimationVariables;
import immersive_aircraft.util.InterpolatedFloat;
import immersive_aircraft.util.Utils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Simulated engine behavior
 */
public abstract class EngineVehicle extends InventoryVehicleEntity {
    protected static final EntityDataAccessor<Float> ENGINE = SynchedEntityData.defineId(EngineVehicle.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> UTILIZATION = SynchedEntityData.defineId(EngineVehicle.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> LOW_ON_FUEL = SynchedEntityData.defineId(EngineVehicle.class, EntityDataSerializers.BOOLEAN);

    public final InterpolatedFloat engineRotation = new InterpolatedFloat();
    public final InterpolatedFloat enginePower = new InterpolatedFloat(20.0f);
    public float engineSpinUpStrength = 0.0f;
    public float engineSound = 0.0f;

    protected enum FuelState {
        NEVER,
        EMPTY,
        FUELED,
        LOW
    }

    FuelState lastFuelState = FuelState.NEVER;

    public static final int TARGET_FUEL = 1000;
    public static final int LOW_FUEL = 900;

    private final int[] fuel;

    public enum GUI_STYLE {
        NONE,
        ENGINE
    }

    public GUI_STYLE getGuiStyle() {
        return GUI_STYLE.ENGINE;
    }

    public EngineVehicle(EntityType<? extends EngineVehicle> entityType, Level world, boolean canExplodeOnCrash) {
        super(entityType, world, canExplodeOnCrash);

        fuel = new int[getInventoryDescription().getSlots(VehicleInventoryDescription.SlotType.BOILER).size()];
    }

    protected SoundEvent getEngineStartSound() {
        return Sounds.ENGINE_START.get();
    }

    protected SoundEvent getEngineSound() {
        return Sounds.PROPELLER.get();
    }

    protected float getEnginePitch() {
        return 1.0f;
    }

    protected float getEngineReactionSpeed() {
        return 20.0f;
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();

        entityData.define(ENGINE, 0.0f);
        entityData.define(UTILIZATION, 0.0f);
        entityData.define(LOW_ON_FUEL, false);
    }

    @Override
    public void tick() {
        super.tick();

        // adapt engine reaction time
        enginePower.setSteps(getEngineReactionSpeed() / getProperties().get(VehicleStat.ACCELERATION));

        // spin up the engine
        enginePower.update(getEngineTarget() * (wasTouchingWater ? 0.1f : 1.0f));

        // simulate spin up
        engineSpinUpStrength = Math.max(0.0f, engineSpinUpStrength + enginePower.getDiff() - 0.01f);

        // rotate propeller
        if (level().isClientSide()) {
            engineRotation.update((engineRotation.getValue() + getEnginePower()) % 1000);
        }

        // shutdown
        if (!isVehicle() && getEngineTarget() > 0) {
            setEngineTarget(0.0f);
        }

        // Engine sounds
        if (level().isClientSide) {
            engineSound += getEnginePower() * 0.25f;
            if (engineSound > 1.0f) {
                engineSound--;
                if (isFuelLow()) {
                    engineSound -= random.nextInt(2);
                }
                level().playLocalSound(getX(), getY(), getZ(), getEngineSound(), getSoundSource(), Math.min(1.0f, 0.25f + engineSpinUpStrength), (random.nextFloat() * 0.1f + 0.95f) * getEnginePitch(), false);
            }
        }

        // Fuel
        if (fuel.length > 0 && !level().isClientSide) {
            float consumption = getFuelConsumption();
            while (consumption > 0 && (consumption >= 1 || random.nextFloat() < consumption)) {
                for (int i = 0; i < fuel.length; i++) {
                    if (fuel[i] > 0) {
                        fuel[i]--;
                    }
                }
                consumption--;
            }
        }

        // Refuel
        if (isVehicle()) {
            refuel();

            // Fuel notification
            if (getControllingPassenger() instanceof ServerPlayer player) {
                float utilization = getFuelUtilization();
                if (utilization > 0 && isFuelLow()) {
                    if (lastFuelState != FuelState.LOW) {
                        player.displayClientMessage(Component.translatable("immersive_aircraft." + getFuelType() + ".low"), true);
                        lastFuelState = FuelState.LOW;
                    }
                } else if (utilization > 0) {
                    lastFuelState = FuelState.FUELED;
                } else {
                    if (lastFuelState != FuelState.EMPTY) {
                        player.displayClientMessage(Component.translatable("immersive_aircraft." + getFuelType() + "." + (lastFuelState == FuelState.FUELED ? "out" : "none")), true);
                        lastFuelState = FuelState.EMPTY;
                    }
                }
            }
        } else {
            lastFuelState = FuelState.NEVER;
        }
    }

    protected boolean isFuelLow() {
        if (!Config.getInstance().burnFuelInCreative && isPilotCreative()) {
            return false;
        }

        if (level().isClientSide) {
            return entityData.get(LOW_ON_FUEL);
        } else {
            boolean low = true;
            for (int i : fuel) {
                if (i > LOW_FUEL) {
                    low = false;
                    break;
                }
            }
            entityData.set(LOW_ON_FUEL, low);
            return low;
        }
    }

    protected String getFuelType() {
        return "fuel";
    }

    float getFuelConsumption() {
        return getEngineTarget() * getProperties().get(VehicleStat.FUEL) * Config.getInstance().fuelConsumption;
    }

    private void refuel(int i) {
        List<VehicleInventoryDescription.SlotDescription> slots = getInventoryDescription().getSlots(VehicleInventoryDescription.SlotType.BOILER);
        while (fuel[i] <= TARGET_FUEL && i < slots.size()) {
            ItemStack stack = getInventory().getItem(slots.get(i).index());
            int time = Utils.getFuelTime(stack);
            if (time > 0) {
                fuel[i] += time;
                Item item = stack.getItem();
                stack.shrink(1);
                if (stack.isEmpty()) {
                    Item remainingItem = item.getCraftingRemainingItem();
                    getInventory().setItem(slots.get(i).index(), remainingItem == null ? ItemStack.EMPTY : new ItemStack(remainingItem));
                }
            } else {
                break;
            }
        }
    }

    private void refuel() {
        for (int i = 0; i < fuel.length; i++) {
            refuel(i);
        }
    }

    public float getEnginePower() {
        return (float) (enginePower.getSmooth() * Math.sqrt(getFuelUtilization()));
    }

    public float getEngineTarget() {
        return entityData.get(ENGINE);
    }

    public void setEngineTarget(float engineTarget) {
        if (getFuelUtilization() > 0 || engineTarget == 0) {
            if (level().isClientSide) {
                if (getEngineTarget() != engineTarget) {
                    NetworkHandler.sendToServer(new EnginePowerMessage(engineTarget));
                }
                if (getFuelUtilization() > 0 && getEngineTarget() == 0.0 && engineTarget > 0) {
                    level().playLocalSound(getX(), getY(), getZ(), getEngineStartSound(), getSoundSource(), 1.0f, getEnginePitch(), false);
                }
            }
            entityData.set(ENGINE, engineTarget);
        }
    }

    public float getFuelUtilization() {
        if (Config.getInstance().fuelConsumption == 0) {
            return 1.0f;
        }
        if (!Config.getInstance().burnFuelInCreative && isPilotCreative()) {
            return 1.0f;
        }
        if (level().isClientSide) {
            return entityData.get(UTILIZATION);
        } else {
            int running = 0;
            for (int i : fuel) {
                if (i > 0) {
                    running++;
                }
            }
            float utilization = (float) running / fuel.length * (isFuelLow() ? 0.75f : 1.0f);
            entityData.set(UTILIZATION, utilization);
            return utilization;
        }
    }

    @Override
    public void setAnimationVariables(float tickDelta) {
        super.setAnimationVariables(tickDelta);

        BBAnimationVariables.set("engine_rotation", engineRotation.getSmooth(tickDelta));
    }
}
