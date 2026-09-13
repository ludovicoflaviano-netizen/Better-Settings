package com.bettersettings;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.*;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.io.IOException;
import java.util.*;

public final class BetterSettingsPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private final Map<UUID, Map<String, Boolean>> settings = new HashMap<>();
    private File dataFile;
    private YamlConfiguration data;

    @Override public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "player-settings.yml");
        if (!dataFile.exists()) { try { getDataFolder().mkdirs(); dataFile.createNewFile(); } catch (IOException e) { getLogger().severe("Could not create player-settings.yml: " + e.getMessage()); } }
        data = YamlConfiguration.loadConfiguration(dataFile);
        loadAll();
        Objects.requireNonNull(getCommand("bettersettings")).setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Better Settings enabled with 120 personal settings and native Paper dialogs.");
    }
    @Override public void onDisable() { saveAll(); }

    private void loadAll() {
        for (String uuid : data.getKeys(false)) {
            try { UUID id=UUID.fromString(uuid); Map<String,Boolean> map=new HashMap<>(); for(String k:data.getConfigurationSection(uuid).getKeys(false)) map.put(k,data.getBoolean(uuid+"."+k)); settings.put(id,map); } catch (Exception ignored) {}
        }
    }
    private void saveAll() {
        if (data==null) return;
        for (var entry:settings.entrySet()) for (var s:entry.getValue().entrySet()) data.set(entry.getKey()+"."+s.getKey(),s.getValue());
        try { data.save(dataFile); } catch(IOException e) { getLogger().severe("Could not save settings: "+e.getMessage()); }
    }
    private Map<String,Boolean> prefs(Player p) { return settings.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()); }
    private boolean enabled(Player p,String key) { return prefs(p).getOrDefault(key,true); }
    private void set(Player p,String key,boolean value) { prefs(p).put(key,value); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (!p.hasPermission("bettersettings.use")) return true;
        if (args.length==0) { p.showDialog(mainDialog()); return true; }
        if (args.length==1) {
            if (args[0].equalsIgnoreCase("reload")) { if(!p.hasPermission("bettersettings.admin")){p.sendMessage(Component.text("No permission.",NamedTextColor.RED));return true;} reloadConfig(); return true; }
            if (categoryKeys().contains(args[0].toLowerCase())) { p.showDialog(categoryDialog(p,args[0].toLowerCase())); return true; }
        }
        if (args.length>=17 && args[0].equalsIgnoreCase("apply")) { applyCategory(p,args); return true; }
        p.sendMessage(Component.text("Usage: /bettersettings [category]",NamedTextColor.GRAY)); return true;
    }

    private Set<String> categoryKeys(){ return Set.of("gameplay","combat","movement","visual","chat","items","world","utility"); }
    private void applyCategory(Player p,String[] args){
        String cat=args[1]; List<String> keys=keys(cat); if(keys==null || args.length<keys.size()+2) return;
        for(int i=0;i<keys.size();i++) set(p,keys.get(i),Boolean.parseBoolean(args[i+2]));
        saveAll(); applyEffects(p); if(getConfig().getBoolean("apply-message",true)) p.sendActionBar(Component.text("Settings saved",NamedTextColor.GREEN));
    }

    private Dialog mainDialog(){
        List<ActionButton> buttons=new ArrayList<>();
        Map<String,String> icons=Map.of("gameplay","GRASS_BLOCK","combat","DIAMOND_SWORD","movement","FEATHER","visual","ENDER_EYE","chat","WRITABLE_BOOK","items","DIAMOND_PICKAXE","world","COMPASS","utility","CLOCK");
        for(String cat:categoryKeys()) buttons.add(button(iconButton(cat,icons.get(cat))));
        return Dialog.create(builder -> builder.empty()
            .base(DialogBase.builder(Component.text("Better Settings",NamedTextColor.WHITE))
                .body(List.of(DialogBody.plainMessage(Component.text("Personal settings • 120 options",NamedTextColor.GRAY))))
                .canCloseWithEscape(true).build())
            .type(DialogType.multiAction(buttons,null,2)));
    }
    private ActionButton button(ActionButton b){return b;}
    private ActionButton iconButton(String cat,String material){
        Material m=Material.matchMaterial(material);
        ItemStack icon=new ItemStack(m==null?Material.PAPER:m);
        String title=pretty(cat);
        return ActionButton.builder(Component.text("◆ "+title,NamedTextColor.WHITE))
            .tooltip(Component.text("Open "+title+" settings",NamedTextColor.GRAY))
            .action(DialogAction.commandTemplate("bettersettings "+cat)).width(180).build();
    }

    private Dialog categoryDialog(Player player,String cat){
        List<String> ks=keys(cat); List<DialogInput> inputs=new ArrayList<>();
        for(String key:ks) inputs.add(DialogInput.bool(key,Component.text(pretty(key),NamedTextColor.WHITE),enabled(player,key),"true","false"));
        List<ActionButton> actions=new ArrayList<>();
        StringBuilder command=new StringBuilder("bettersettings apply ").append(cat); for(String key:ks) command.append(" $(").append(key).append(")");
        actions.add(ActionButton.builder(Component.text("Save",NamedTextColor.GREEN)).action(DialogAction.commandTemplate(command.toString())).width(160).build());
        actions.add(ActionButton.builder(Component.text("Back",NamedTextColor.GRAY)).action(DialogAction.commandTemplate("bettersettings")).width(160).build());
        return Dialog.create(builder -> builder.empty()
            .base(DialogBase.builder(Component.text(pretty(cat)+" Settings",NamedTextColor.WHITE))
                .body(List.of(DialogBody.item(new ItemStack(Material.matchMaterial(iconMaterial(cat))==null?Material.PAPER:Material.matchMaterial(iconMaterial(cat))), null, true, true, 64, 64), DialogBody.plainMessage(Component.text("Toggle your personal options below.",NamedTextColor.GRAY))))
                .inputs(inputs).canCloseWithEscape(true).build())
            .type(DialogType.multiAction(actions,null,2)));
    }

    private String iconMaterial(String cat){ return switch(cat){case "gameplay"->"GRASS_BLOCK";case "combat"->"DIAMOND_SWORD";case "movement"->"FEATHER";case "visual"->"ENDER_EYE";case "chat"->"WRITABLE_BOOK";case "items"->"DIAMOND_PICKAXE";case "world"->"COMPASS";default->"CLOCK";};}
    private String pretty(String raw){ String s=raw.replace('_',' '); return Character.toUpperCase(s.charAt(0))+s.substring(1); }

    private List<String> keys(String cat){ return switch(cat){
        case "gameplay" -> List.of("gameplay_auto_sprint","gameplay_keep_inventory","gameplay_keep_experience","gameplay_no_fall_damage","gameplay_no_fire_damage","gameplay_no_drowning","gameplay_no_void_damage","gameplay_no_freeze_damage","gameplay_no_explosion_damage","gameplay_no_projectile_damage","gameplay_no_magic_damage","gameplay_no_contact_damage","gameplay_no_lava_damage","gameplay_safe_respawn","gameplay_damage_alerts");
        case "combat" -> List.of("combat_combat_alerts","combat_attack_sounds","combat_hit_particles","combat_crit_particles","combat_damage_numbers","combat_low_health_warning","combat_target_highlight","combat_auto_aim_hint","combat_shield_reminder","combat_totem_reminder","combat_weapon_durability_alert","combat_armor_durability_alert","combat_potion_alerts","combat_combat_timer","combat_combat_sounds");
        case "movement" -> List.of("movement_auto_sprint","movement_sprint_toggle","movement_jump_feedback","movement_step_feedback","movement_fall_feedback","movement_velocity_feedback","movement_speed_effect","movement_jump_effect","movement_slow_fall_effect","movement_dolphins_grace","movement_water_breathing","movement_climb_feedback","movement_elytra_alert","movement_horse_speed_alert","movement_boat_speed_alert");
        case "visual" -> List.of("visual_night_vision","visual_bright_effects","visual_potion_effect_alerts","visual_weather_alerts","visual_time_alerts","visual_fire_alerts","visual_portal_alerts","visual_darkness_alerts","visual_sculk_alerts","visual_light_level_alerts","visual_entity_alerts","visual_rare_entity_alerts","visual_item_glow","visual_named_item_glow","visual_bossbar_alerts");
        case "chat" -> List.of("chat_join_messages","chat_quit_messages","chat_death_messages","chat_advancement_messages","chat_chat_timestamps","chat_chat_sound","chat_mention_sound","chat_mention_highlight","chat_private_message_sound","chat_command_feedback","chat_system_message_sound","chat_chat_filter_notice","chat_spam_notice","chat_chat_scroll_alert","chat_welcome_message");
        case "items" -> List.of("items_item_pickup_sound","items_item_drop_sound","items_item_break_alert","items_item_low_durability","items_tool_break_alert","items_armor_break_alert","items_food_alert","items_potion_alert","items_arrow_alert","items_block_alert","items_container_alert","items_inventory_full_alert","items_xp_pickup_sound","items_rare_item_alert","items_enchanted_item_alert");
        case "world" -> List.of("world_biome_alerts","world_structure_alerts","world_chunk_alerts","world_portal_alerts","world_weather_alerts","world_thunder_alerts","world_time_alerts","world_moon_phase_alerts","world_sleep_alerts","world_bed_alerts","world_spawn_alerts","world_village_alerts","world_raid_alerts","world_trial_alerts","world_end_alerts");
        case "utility" -> List.of("utility_actionbar_status","utility_coordinates","utility_direction","utility_ping_display","utility_tps_display","utility_memory_display","utility_online_count","utility_clock_display","utility_fps_hint","utility_server_tip","utility_tutorial_hints","utility_command_suggestions","utility_auto_save_notice","utility_settings_sound","utility_settings_messages");
        default -> null; }; }

    private boolean any(Player p,String suffix){ for(String cat:categoryKeys()) if(enabled(p,cat+"_"+suffix)) return true; return false; }
    private void applyEffects(Player p){
        if(enabled(p,"visual_night_vision")) p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,220,0,true,false,false)); else p.removePotionEffect(PotionEffectType.NIGHT_VISION);
        if(enabled(p,"movement_speed_effect")) p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,220,0,true,false,false)); else p.removePotionEffect(PotionEffectType.SPEED);
        if(enabled(p,"movement_jump_effect")) p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST,220,0,true,false,false)); else p.removePotionEffect(PotionEffectType.JUMP_BOOST);
        if(enabled(p,"movement_slow_fall_effect")) p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,220,0,true,false,false)); else p.removePotionEffect(PotionEffectType.SLOW_FALLING);
        if(enabled(p,"movement_water_breathing")) p.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING,220,0,true,false,false)); else p.removePotionEffect(PotionEffectType.WATER_BREATHING);
        if(enabled(p,"movement_dolphins_grace")) p.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE,220,0,true,false,false)); else p.removePotionEffect(PotionEffectType.DOLPHINS_GRACE);
    }

    @EventHandler public void onJoin(PlayerJoinEvent e){ applyEffects(e.getPlayer()); if(getConfig().getBoolean("open-message",false)) e.getPlayer().sendMessage(Component.text("Use /bettersettings to open your personal settings.",NamedTextColor.GRAY)); }
    @EventHandler public void onQuit(PlayerQuitEvent e){ saveAll(); }
    @EventHandler public void onMove(PlayerMoveEvent e){
        Player p=e.getPlayer(); if(enabled(p,"gameplay_auto_sprint")||enabled(p,"movement_auto_sprint")){ if(p.isSprinting()==false && p.getVelocity().lengthSquared()>0.02 && p.getFoodLevel()>0) p.setSprinting(true); }
        if(enabled(p,"movement_speed_effect")||enabled(p,"movement_jump_effect")||enabled(p,"movement_slow_fall_effect")) applyEffects(p);
    }
    @EventHandler public void onDamage(EntityDamageEvent e){
        if(!(e.getEntity() instanceof Player p)) return;
        if(enabled(p,"gameplay_no_fall_damage") && e.getCause()==EntityDamageEvent.DamageCause.FALL) e.setCancelled(true);
        if(enabled(p,"gameplay_no_fire_damage") && (e.getCause()==EntityDamageEvent.DamageCause.FIRE || e.getCause()==EntityDamageEvent.DamageCause.FIRE_TICK || e.getCause()==EntityDamageEvent.DamageCause.HOT_FLOOR)) e.setCancelled(true);
        if(enabled(p,"gameplay_no_drowning") && e.getCause()==EntityDamageEvent.DamageCause.DROWNING) e.setCancelled(true);
        if(enabled(p,"gameplay_no_void_damage") && e.getCause()==EntityDamageEvent.DamageCause.VOID) e.setCancelled(true);
        if(enabled(p,"gameplay_no_freeze_damage") && e.getCause()==EntityDamageEvent.DamageCause.FREEZE) e.setCancelled(true);
        if(enabled(p,"gameplay_no_explosion_damage") && (e.getCause()==EntityDamageEvent.DamageCause.ENTITY_EXPLOSION || e.getCause()==EntityDamageEvent.DamageCause.BLOCK_EXPLOSION)) e.setCancelled(true);
        if(enabled(p,"gameplay_no_projectile_damage") && e.getCause()==EntityDamageEvent.DamageCause.PROJECTILE) e.setCancelled(true);
        if(enabled(p,"gameplay_no_magic_damage") && (e.getCause()==EntityDamageEvent.DamageCause.MAGIC || e.getCause()==EntityDamageEvent.DamageCause.DRAGON_BREATH)) e.setCancelled(true);
        if(enabled(p,"gameplay_no_contact_damage") && e.getCause()==EntityDamageEvent.DamageCause.CONTACT) e.setCancelled(true);
        if(enabled(p,"gameplay_no_lava_damage") && e.getCause()==EntityDamageEvent.DamageCause.LAVA) e.setCancelled(true);
    }
    @EventHandler public void onDeath(PlayerDeathEvent e){
        Player p=e.getEntity(); if(enabled(p,"gameplay_keep_inventory")){ e.setKeepInventory(true); e.getDrops().clear(); }
        if(enabled(p,"gameplay_keep_experience")){ e.setKeepLevel(true); e.setDroppedExp(0); }
    }
}
