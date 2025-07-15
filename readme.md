# Safeslot

**Safeslot** is a lightweight and reliable inventory backup and restoration mod for Minecraft servers, built to protect players from accidental losses. Whether from death, glitches, or inventory mishaps, Safeslot automatically captures inventory snapshots and allows server admins to **view**, **restore**, and **export** past inventory states. Built for **Fabric**.

**This mod is still under development. If you encounter bugs or have feature suggestions, please submit an issue on GitHub, feedback is always welcome!**

---

- **Automatic Backups**
  Inventory states are saved automatically when a player **leaves** the server, capturing all items.

- **Manual Backup Support**
  Admins can create backups manually via command, giving full control over when snapshots are taken.

- **Inventory Browsing**
  View available backups for any player, including timestamps and details of when each snapshot was taken.

- **Selective Restoration**
  Restore any saved inventory snapshot back to the player, by default, the most recent backup is used, but specific versions can be selected.

- **Data Export**
  Export inventory data to external files or systems for record-keeping or debugging purposes.

- **Automatic Cleanup**
  Maintains a limited number of backups per player (default: 20), automatically deleting older snapshots to save space.

- **Permission-Based Access**
  All features are protected by permissions, allowing fine-grained access control for moderators and admins.

---

```
/safeslot inventoryrestore view <player>
```

View all available inventory backups for the specified player.

```
/safeslot inventoryrestore restore <player> [backup]
```

Restore a player’s inventory from a specific backup (or the latest if none specified).

```
/safeslot inventoryrestore save <player>
```

Manually create an inventory backup for the player.

```
/safeslot inventoryrestore cleanup
```

Clean up older backups, keeping the most recent three for each player.

---

## Permissions

- `safeslot.feature.inventoryrestore.view` – View available backups
- `safeslot.feature.inventoryrestore.restore` – Restore a player’s inventory
- `safeslot.feature.inventoryrestore.manualbackup` – Create manual backups
- `safeslot.feature.inventoryrestore.cleanup` – Perform cleanup of old backups

_Operators always have access to all features._

---

## Use Cases

- **Death Recovery**
  Let admins quickly restore a player's gear and items after accidental or unfair death.

- **Rollback Inventory Bugs**
  Recover from inventory corruption or mod-related desync issues without manual intervention.

- **Item Theft Resolution**
  Inspect inventory states before and after a suspected theft or issue.

- **Server Moderation Tools**
  Provide staff with safe, permission-based tools for restoring player items.

- **Inventory History Tracking**
  Keep a short history of player inventories for auditing or debugging purposes.

---

## Installation

1. Download the latest JAR from the [releases page](#).
2. Place it in your server's `mods` folder.
3. Restart your server.
4. Set permissions for your moderation team as needed.

---

## Requirements

- **Minecraft** `1.20.1`
- **Fabric Loader** `0.15.7+`
- **Fabric API** `0.92.0+1.20.1`
- \*\*fabric-permissions-api\`
