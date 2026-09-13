package com.bettersettings;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.registry.data.dialog.*;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
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
        startStatusTask();
        getLogger().info("Better Settings enabled with 121 personal settings and native Paper dialogs.");
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
        if (args.length==0 || args[0].equalsIgnoreCase("open")) { p.showDialog(mainDialog()); return true; }
        if (args.length==1) {
            if (args[0].equalsIgnoreCase("reload")) { if(!p.hasPermission("bettersettings.admin")){p.sendMessage(Component.text("No permission.",NamedTextColor.RED));return true;} reloadConfig(); return true; }
            if (categoryKeys().contains(args[0].toLowerCase())) { p.showDialog(categoryDialog(p,args[0].toLowerCase())); return true; }
        }
        if (args.length>=2 && args[0].equalsIgnoreCase("apply")) { applyCategory(p,args); return true; }
        p.sendMessage(Component.text("Usage: /bettersettings [category]",NamedTextColor.GRAY)); return true;
    }

    private Set<String> categoryKeys(){ return Set.of("gameplay","combat","movement","visual","chat","items","world","utility"); }
    private void applyCategory(Player p,String[] args){
        String cat=args[1].toLowerCase(Locale.ROOT); List<String> keys=keys(cat);
        if(keys==null || args.length != keys.size()+2) { p.sendMessage(Component.text("Invalid settings payload. Open the menu again with /bettersettings.",NamedTextColor.RED)); return; }
        for(int i=0;i<keys.size();i++) set(p,keys.get(i),Boolean.parseBoolean(args[i+2]));
        saveAll(); applyEffects(p); if(getConfig().getBoolean("apply-message",true)) p.sendActionBar(Component.text("Settings saved",NamedTextColor.GREEN));
    }

    private Dialog mainDialog(){
        List<ActionButton> buttons=new ArrayList<>();
        Map<String,String> icons=Map.of("gameplay","GRASS_BLOCK","combat","DIAMOND_SWORD","movement","FEATHER","visual","ENDER_EYE","chat","WRITABLE_BOOK","items","DIAMOND_PICKAXE","world","COMPASS","utility","CLOCK");
        for(String cat:categoryKeys()) buttons.add(iconButton(cat,icons.get(cat)));
        return Dialog.create(builder -> builder.empty()
            .base(DialogBase.builder(Component.text("Better Settings",NamedTextColor.WHITE))
                .body(List.of(DialogBody.plainMessage(Component.text("Personal settings • 121 options",NamedTextColor.GRAY)),
                              DialogBody.plainMessage(Component.text("Select a category. Changes are saved directly by the native Paper dialog.",NamedTextColor.DARK_GRAY))))
                .canCloseWithEscape(true).build())
            .type(DialogType.multiAction(buttons,null,2)));
    }
    private ActionButton iconButton(String cat,String material){
        Material m=Material.matchMaterial(material);
        String title=pretty(cat);
        return ActionButton.builder(Component.text("◆ "+title,NamedTextColor.WHITE))
            .tooltip(Component.text("Open "+title+" settings",NamedTextColor.GRAY))
            .action(DialogAction.customClick(Key.key("bettersettings:open/"+cat),null)).width(180).build();
    }

    private Dialog categoryDialog(Player player,String cat){
        List<String> ks=keys(cat); List<DialogInput> inputs=new ArrayList<>();
        for(String key:ks) inputs.add(DialogInput.bool(key,Component.text(pretty(key),NamedTextColor.WHITE),enabled(player,key),"true","false"));
        List<ActionButton> actions=new ArrayList<>();
        actions.add(ActionButton.builder(Component.text("Save",NamedTextColor.GREEN))
            .action(DialogAction.customClick(Key.key("bettersettings:save/"+cat),null)).width(160).build());
        actions.add(ActionButton.builder(Component.text("Back",NamedTextColor.GRAY))
            .action(DialogAction.customClick(Key.key("bettersettings:back"),null)).width(160).build());
        Material icon=Material.matchMaterial(iconMaterial(cat));
        return Dialog.create(builder -> builder.empty()
            .base(DialogBase.builder(Component.text(pretty(cat)+" Settings",NamedTextColor.WHITE))
                .body(List.of(DialogBody.item(new ItemStack(icon==null?Material.PAPER:icon),null,true,true,64,64),
                              DialogBody.plainMessage(Component.text("Toggle your personal options below. Save applies them immediately.",NamedTextColor.GRAY))))
                .inputs(inputs).canCloseWithEscape(true).build())
            .type(DialogType.multiAction(actions,null,2)));
    }

    @EventHandler
    public void onDialogClick(PlayerCustomClickEvent event){
        String id=event.getIdentifier().asString();
        if(!id.startsWith("bettersettings:")) return;
        if(!(event.getCommonConnection() instanceof PlayerGameConnection conn)) return;
        Player p=conn.getPlayer();
        if(id.startsWith("bettersettings:open/")){
            String cat=id.substring("bettersettings:open/".length()).toLowerCase(Locale.ROOT);
            if(categoryKeys().contains(cat)) p.showDialog(categoryDialog(p,cat));
            return;
        }
        if(id.equals("bettersettings:back")){ p.showDialog(mainDialog()); return; }
        if(id.startsWith("bettersettings:save/")){
            String cat=id.substring("bettersettings:save/".length()).toLowerCase(Locale.ROOT);
            List<String> ks=keys(cat); DialogResponseView view=event.getDialogResponseView();
            if(view==null || ks==null) return;
            for(String key:ks){ Boolean value=view.getBoolean(key); if(value!=null) set(p,key,value); }
            saveAll(); applyEffects(p);
            p.sendActionBar(Component.text("✓ "+pretty(cat)+" settings saved",NamedTextColor.GREEN));
            p.showDialog(categoryDialog(p,cat));
        }
    }

    private String iconMaterial(String cat){ return switch(cat){case "gameplay"->"GRASS_BLOCK";case "combat"->"DIAMOND_SWORD";case "movement"->"FEATHER";case "visual"->"ENDER_EYE";case "chat"->"WRITABLE_BOOK";case "items"->"DIAMOND_PICKAXE";case "world"->"COMPASS";default->"CLOCK";};}
    private String pretty(String raw){ String s=raw.replace('_',' '); return Character.toUpperCase(s.charAt(0))+s.substring(1); }

    private List<String> keys(String cat){ return switch(cat){
        case "gameplay" -> List.of("gameplay_auto_sprint","gameplay_keep_inventory","gameplay_keep_experience","gameplay_no_fall_damage","gameplay_no_fire_damage","gameplay_no_drowning","gameplay_no_void_damage","gameplay_no_freeze_damage","gameplay_no_explosion_damage","gameplay_no_projectile_damage","gameplay_no_magic_damage","gameplay_no_contact_damage","gameplay_no_lava_damage","gameplay_safe_respawn","gameplay_damage_alerts");
        case "combat" -> List.of("combat_combat_alerts","combat_attack_sounds","combat_hit_particles","combat_crit_particles","combat_damage_numbers","combat_low_health_warning","combat_target_highlight","combat_auto_aim_hint","combat_shield_reminder","combat_totem_reminder","combat_weapon_durability_alert","combat_armor_durability_alert","combat_potion_alerts","combat_combat_timer","combat_combat_sounds");
        case "movement" -> List.of("movement_auto_sprint","movement_sprint_toggle","movement_jump_feedback","movement_step_feedback","movement_fall_feedback","movement_velocity_feedback","movement_speed_effect","movement_jump_effect","movement_slow_fall_effect","movement_dolphins_grace","movement_water_breathing","movement_climb_feedback","movement_elytra_alert","movement_horse_speed_alert","movement_boat_speed_alert");
        case "visual" -> List.of("visual_night_vision","visual_bright_effects","visual_potion_effect_alerts","visual_weather_alerts","visual_time_alerts","visual_fire_alerts","visual_portal_alerts","visual_darkness_alerts","visual_sculk_alerts","visual_light_level_alerts","visual_entity_alerts","visual_rare_entity_alerts","visual_item_glow","visual_named_item_glow","visual_bossbar_alerts","visual_silent_spawn");
        case "chat" -> List.of("chat_join_messages","chat_quit_messages","chat_death_messages","chat_advancement_messages","chat_chat_timestamps","chat_chat_sound","chat_mention_sound","chat_mention_highlight","chat_private_message_sound","chat_command_feedback","chat_system_message_sound","chat_chat_filter_notice","chat_spam_notice","chat_chat_scroll_alert","chat_welcome_message");
        case "items" -> List.of("items_item_pickup_sound","items_item_drop_sound","items_item_break_alert","items_item_low_durability","items_tool_break_alert","items_armor_break_alert","items_food_alert","items_potion_alert","items_arrow_alert","items_block_alert","items_container_alert","items_inventory_full_alert","items_xp_pickup_sound","items_rare_item_alert","items_enchanted_item_alert");
        case "world" -> List.of("world_biome_alerts","world_structure_alerts","world_chunk_alerts","world_portal_alerts","world_weather_alerts","world_thunder_alerts","world_time_alerts","world_moon_phase_alerts","world_sleep_alerts","world_bed_alerts","world_spawn_alerts","world_village_alerts","world_raid_alerts","world_trial_alerts","world_end_alerts");
        case "utility" -> List.of("utility_actionbar_status","utility_coordinates","utility_direction","utility_ping_display","utility_tps_display","utility_memory_display","utility_online_count","utility_clock_display","utility_fps_hint","utility_server_tip","utility_tutorial_hints","utility_command_suggestions","utility_auto_save_notice","utility_settings_sound","utility_settings_messages");
        default -> null; }; }

    private boolean any(Player p,String suffix){ for(String cat:categoryKeys()) if(enabled(p,cat+"_"+suffix)) return true; return false; }
    private void applyEffects(Player p){
        effect(p,PotionEffectType.NIGHT_VISION,"visual_night_vision");
        effect(p,PotionEffectType.SPEED,"movement_speed_effect");
        effect(p,PotionEffectType.JUMP_BOOST,"movement_jump_effect");
        effect(p,PotionEffectType.SLOW_FALLING,"movement_slow_fall_effect");
        effect(p,PotionEffectType.WATER_BREATHING,"movement_water_breathing");
        effect(p,PotionEffectType.DOLPHINS_GRACE,"movement_dolphins_grace");
    }
    private void effect(Player p,PotionEffectType type,String key){
        if(enabled(p,key)) p.addPotionEffect(new PotionEffect(type,220,0,true,false,false));
        else p.removePotionEffect(type);
    }

    private void startStatusTask(){
        Bukkit.getScheduler().runTaskTimer(this,()->{
            for(Player p:Bukkit.getOnlinePlayers()){
                applyEffects(p);
                String msg=null;
                if(enabled(p,"utility_coordinates")) msg="XYZ "+p.getLocation().getBlockX()+" "+p.getLocation().getBlockY()+" "+p.getLocation().getBlockZ();
                else if(enabled(p,"utility_ping_display")) msg="Ping: "+p.getPing()+"ms";
                else if(enabled(p,"utility_online_count")) msg="Online: "+Bukkit.getOnlinePlayers().size();
                else if(enabled(p,"utility_clock_display")) msg="Time: "+p.getWorld().getTime();
                else if(enabled(p,"utility_memory_display")) msg="Memory: "+((Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1024/1024)+" MB";
                else if(enabled(p,"visual_time_alerts")) msg="World time: "+p.getWorld().getTime();
                else if(enabled(p,"visual_weather_alerts")) msg=p.getWorld().hasStorm()?"Weather: rain":"Weather: clear";
                else if(enabled(p,"visual_light_level_alerts")) msg="Light level: "+p.getLocation().getBlock().getLightLevel();
                else if(enabled(p,"combat_low_health_warning") && p.getHealth()<=p.getMaxHealth()*0.30) msg="LOW HEALTH: "+Math.ceil(p.getHealth());
                else if(enabled(p,"utility_actionbar_status")) msg="Better Settings active";
                if(msg!=null) p.sendActionBar(Component.text(msg,NamedTextColor.GRAY));
            }
        },20L,20L);
    }

    @EventHandler public void onJoin(PlayerJoinEvent e){ Player p=e.getPlayer(); applyEffects(p); if(!enabled(p,"chat_join_messages") || enabled(p,"visual_silent_spawn")) e.setJoinMessage(null); if(getConfig().getBoolean("open-message",false)) p.sendMessage(Component.text("Use /bettersettings to open your personal settings.",NamedTextColor.GRAY)); }
    @EventHandler public void onQuit(PlayerQuitEvent e){ if(!enabled(e.getPlayer(),"chat_quit_messages")) e.setQuitMessage(null); saveAll(); }
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
    @EventHandler public void onCombat(EntityDamageByEntityEvent e){
        if(!(e.getDamager() instanceof Player p)) return;
        if(enabled(p,"combat_attack_sounds")||enabled(p,"combat_combat_sounds")) p.playSound(p.getLocation(),Sound.ENTITY_PLAYER_ATTACK_STRONG,0.7f,1.0f);
        if(enabled(p,"combat_hit_particles")||enabled(p,"combat_crit_particles")) p.getWorld().spawnParticle(enabled(p,"combat_crit_particles")?Particle.CRIT:Particle.DAMAGE_INDICATOR,e.getEntity().getLocation().add(0,1,0),enabled(p,"combat_crit_particles")?8:4,0.25,0.35,0.25,0.02);
        if(enabled(p,"combat_damage_numbers")) p.sendActionBar(Component.text("Damage: "+String.format(Locale.ROOT,"%.1f",e.getFinalDamage()),NamedTextColor.RED));
    }

    @EventHandler public void onPickup(EntityPickupItemEvent e){
        if(!(e.getEntity() instanceof Player p)) return;
        if(enabled(p,"items_item_pickup_sound")) p.playSound(p.getLocation(),Sound.ENTITY_ITEM_PICKUP,0.7f,1.1f);
        if(enabled(p,"items_rare_item_alert") && e.getItem().getItemStack().getType().getMaxDurability()>0) p.sendActionBar(Component.text("Item picked up: "+e.getItem().getItemStack().getType().key().value(),NamedTextColor.AQUA));
    }

    @EventHandler public void onBreak(BlockBreakEvent e){
        Player p=e.getPlayer();
        if(enabled(p,"items_block_alert")) p.sendActionBar(Component.text("Block: "+e.getBlock().getType().key().value(),NamedTextColor.GRAY));
        if(enabled(p,"world_biome_alerts")) p.sendActionBar(Component.text("Biome: "+p.getLocation().getBlock().getBiome().key().value(),NamedTextColor.GRAY));
    }

    @EventHandler public void onItemDamage(PlayerItemDamageEvent e){
        Player p=e.getPlayer(); ItemStack item=e.getItem(); int max=item.getType().getMaxDurability();
        if(max>0 && (enabled(p,"items_item_low_durability")||enabled(p,"combat_weapon_durability_alert")||enabled(p,"combat_armor_durability_alert"))){
            int left=max-item.getDamage(); if(left<=Math.max(1,max/10)) p.sendActionBar(Component.text("Low durability: "+item.getType().key().value()+" ("+left+")",NamedTextColor.RED));
        }
    }

    @EventHandler public void onItemBreak(PlayerItemBreakEvent e){
        Player p=e.getPlayer(); if(enabled(p,"items_item_break_alert")||enabled(p,"items_tool_break_alert")||enabled(p,"items_armor_break_alert")){
            p.sendActionBar(Component.text("Item broke: "+e.getBrokenItem().getType().key().value(),NamedTextColor.RED));
            p.playSound(p.getLocation(),Sound.ENTITY_ITEM_BREAK,1f,1f);
        }
    }

    @EventHandler public void onWorld(PlayerChangedWorldEvent e){
        Player p=e.getPlayer(); if(enabled(p,"world_portal_alerts")||enabled(p,"world_chunk_alerts")||enabled(p,"world_structure_alerts")) p.sendActionBar(Component.text("World: "+p.getWorld().getName(),NamedTextColor.AQUA));
    }

    @EventHandler public void onChat(AsyncChatEvent e){
        Player p=e.getPlayer();
        if(enabled(p,"chat_chat_sound")||enabled(p,"chat_system_message_sound")) Bukkit.getScheduler().runTask(this,()->p.playSound(p.getLocation(),Sound.BLOCK_NOTE_BLOCK_PLING,0.35f,1.8f));
        if(enabled(p,"chat_chat_timestamps")) Bukkit.getScheduler().runTask(this,()->p.sendActionBar(Component.text("Chat "+new java.text.SimpleDateFormat("HH:mm:ss").format(new Date()),NamedTextColor.GRAY)));
    }

}
