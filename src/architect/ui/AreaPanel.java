package architect.ui;

import architect.*;
import architect.edit.*;
import architect.io.AreaPresets;
import haven.*;
import nurgling.NStyle;

import java.awt.Color;
import java.util.*;

/**
 * Floating panel shown in Area mode: how to draw, the selected area's name and colour, stamping a preset
 * and exporting the plan's areas as a preset for the game.
 */
public class AreaPanel extends Widget {
    public static final int W = UI.scale(330);
    static final int SW = UI.scale(22);
    private final AreaTool tool;
    private final Runnable export;
    private final TextEntry name;
    private final Button del, stampb, exportb, done;
    private Listbox<AreaPresets.Preset> presets = null;
    private List<AreaPresets.Preset> plist = null;
    private String shown = null;
    private final Text.Foundry fnd = new Text.Foundry(Text.sans, 11).aa(true);
    private final Text.Foundry head = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 13).aa(true);
    private final Map<String, Tex> tex = new HashMap<>();
    private Tex title;

    public AreaPanel(AreaTool tool, Runnable export) {
        super(Coord.of(W, UI.scale(150)));
        this.tool = tool;
        this.export = export;
        this.name = add(new TextEntry(W - UI.scale(90), "") {
            public void activate(String text) {
                rename(text);
            }
        }, UI.scale(8), UI.scale(48));
        this.del = add(new Button(UI.scale(70), "Delete", false, () -> {
            PlanArea a = tool.selarea();
            if(a != null)
                tool.planof().removearea(a.id);
            tool.selected = null;
        }), W - UI.scale(78), UI.scale(46));
        int y = UI.scale(108), bw = (W - UI.scale(24)) / 3;
        stampb = add(new Button(bw, "Stamp preset", false, this::togglepresets), UI.scale(8), y);
        exportb = add(new Button(bw, "Export areas", false, export), UI.scale(12) + bw, y);
        done = add(new Button(bw, "Done", false, () -> {
            tool.cancelstamp();
            tool.on = false;
            tool.selected = null;
        }), UI.scale(16) + bw * 2, y);
    }

    private void rename(String text) {
        PlanArea a = tool.selarea();
        if(a != null && !text.trim().isEmpty())
            tool.planof().putarea(a.with(text.trim(), a.color, a.tiles, a.layer));
    }

    private void togglepresets() {
        if(presets != null) {
            presets.destroy();
            presets = null;
            resize(Coord.of(W, UI.scale(150)));
            return;
        }
        if(plist == null)
            plist = AreaPresets.all();
        presets = add(new Listbox<AreaPresets.Preset>(W - UI.scale(16), 10, UI.scale(30)) {
            protected AreaPresets.Preset listitem(int i) {return(plist.get(i));}
            protected int listitems() {return(plist.size());}
            protected void drawbg(GOut g) {g.chcolor(NStyle.infoBg); g.frect(Coord.z, sz); g.chcolor();}
            protected void drawitem(GOut g, AreaPresets.Preset p, int i) {
                g.image(text(p.name, Color.WHITE), Coord.of(UI.scale(6), UI.scale(2)));
                g.image(text(p.category + "  ·  " + p.json.optJSONArray("areas").length() + " areas", new Color(143, 163, 164)), Coord.of(UI.scale(6), UI.scale(16)));
            }
            public void change(AreaPresets.Preset p) {
                super.change(p);
                if(p != null) {
                    tool.stamp(p.json);
                    togglepresets();
                }
            }
        }, UI.scale(8), UI.scale(140));
        resize(Coord.of(W, presets.c.y + presets.sz.y + UI.scale(8)));
    }

    private Tex text(String s, Color c) {
        return(tex.computeIfAbsent(s + "\u0001" + c.getRGB(), k -> fnd.render(s, c).tex()));
    }

    public void tick(double dt) {
        PlanArea a = tool.selarea();
        String id = (a == null) ? null : a.id;
        if(!Objects.equals(id, shown)) {
            shown = id;
            name.settext((a == null) ? "" : a.name);
        }
        if(name.visible != (a != null)) {
            name.show(a != null);
            del.show(a != null);
        }
        super.tick(dt);
    }

    public void draw(GOut g) {
        g.chcolor(NStyle.windowBg);
        g.frect(Coord.z, sz);
        g.chcolor(NStyle.border);
        g.rect(Coord.z, sz);
        g.chcolor();
        if(title == null)
            title = head.render("Areas", NStyle.border).tex();
        g.image(title, Coord.of(UI.scale(8), UI.scale(5)));
        String hint = tool.stamping() ? "Click to place the preset.  R: turn   F: mirror   Esc: cancel"
            : "Drag on the ground to draw an area. Click one to edit it.";
        g.image(text(hint, new Color(223, 231, 230)), Coord.of(UI.scale(8), UI.scale(26)));
        PlanArea a = tool.selarea();
        if(a != null) {
            for(int i = 0; i < AreaTool.PALETTE.length; i++) {
                Color c = AreaTool.PALETTE[i];
                Coord p = Coord.of(UI.scale(8) + i * (SW + UI.scale(4)), UI.scale(76));
                g.chcolor(c.getRed(), c.getGreen(), c.getBlue(), 255);
                g.frect(p, Coord.of(SW, SW));
                if(c.getRGB() == a.color.getRGB()) {
                    g.chcolor(Color.WHITE);
                    g.rect(p.sub(1, 1), Coord.of(SW + 2, SW + 2));
                }
            }
            g.chcolor();
        } else {
            g.image(text("No area selected", new Color(143, 163, 164)), Coord.of(UI.scale(8), UI.scale(54)));
        }
        super.draw(g);
    }

    public boolean mousedown(MouseDownEvent ev) {
        /* Children (buttons, text entries) first: the engine asks this panel before them, and answering
         * true here would swallow every click meant for a button. */
        if(ev.propagate(this) || super.mousedown(ev))
            return(true);
        PlanArea a = tool.selarea();
        if(a != null && ev.c.y >= UI.scale(76) && ev.c.y < UI.scale(76) + SW) {
            int i = (ev.c.x - UI.scale(8)) / (SW + UI.scale(4));
            if(i >= 0 && i < AreaTool.PALETTE.length)
                tool.planof().putarea(a.with(a.name, AreaTool.PALETTE[i], a.tiles, a.layer));
        }
        return(true);
    }
}
