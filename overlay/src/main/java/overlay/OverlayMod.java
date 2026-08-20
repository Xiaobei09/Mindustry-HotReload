package overlay;

import arc.graphics.Color;
import arc.math.Mathf;
import arc.util.Time;
import mindustry.content.Items;
import mindustry.mod.Mod;
import mindustry.type.Category;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.world.Block;
import mindustry.world.blocks.defense.Wall;
import mindustry.world.blocks.power.PowerGenerator;

/**
 * Demo overlay content for the hot-reload workflow.
 *
 * Two reload paths (both without restarting the game):
 *  1. Logic tweaks in the overlay: edit this file, then run
 *     {@code ./gradlew :overlay:syncDev} — the running game detects the change and
 *     reloads the overlay mod in place (fresh classloader, content re-created).
 *  2. Instant tuning without any rebuild: from the in-game JS console type
 *     {@code overlay.OverlayMod.bonus = 5f} — it's read every tick.
 *  3. Core logic (mindustry.* classes): edit, then {@code ./gradlew :core:compileJava --continuous}
 *     hot-swaps the changed classes into the running game via the JVM agent.
 */
public class OverlayMod extends Mod{
    public static Item silver;
    public static Block demoGenerator;
    public static Block demoWall;

    /** Runtime-tweakable logic knob, read every tick. Set it live from the JS console. */
    public static float bonus = 1f;

    @Override
    public void loadContent(){
        //-- new item ----------------------------------------------------------------
        silver = new Item("silver", Color.valueOf("c8d8e8")){{
            hardness = 2;
            cost = 1.2f;
        }};

        //-- new block with custom logic ----------------------------------------------
        demoGenerator = new DemoGenerator("demo-generator"){{
            requirements(Category.power, ItemStack.with(Items.copper, 60, Items.lead, 40));
            size = 2;
            health = 520;
            powerProduction = 1.2f;
        }};

        //-- new plain wall -----------------------------------------------------------
        demoWall = new Wall("demo-wall"){{
            requirements(Category.defense, ItemStack.with(Items.copper, 4));
            size = 1;
            health = 400;
        }};
    }

    /** Block subclass carrying custom logic. The inner *Build class is registered automatically. */
    public static class DemoGenerator extends PowerGenerator{
        public DemoGenerator(String name){
            super(name);
        }

        public class DemoGeneratorBuild extends GeneratorBuild{
            @Override
            public void updateTile(){
                //pulsing production scaled by the runtime-tweakable bonus
                productionEfficiency = enabled ? bonus * (0.8f + Mathf.sin(Time.time / 30f) * 0.2f) : 0f;
            }
        }
    }
}