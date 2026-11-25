package fi.dy.masa.malilib.event;

import java.io.FileFilter;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.sdl.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import fi.dy.masa.malilib.MaLiLib;
import fi.dy.masa.malilib.MaLiLibConfigs;
import fi.dy.masa.malilib.MaLiLibReference;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.hotkeys.*;
import fi.dy.masa.malilib.input.gamepad.GamepadManager;
import fi.dy.masa.malilib.input.gamepad.GamepadButtonEvent;
import fi.dy.masa.malilib.util.FileNameUtils;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.input.*;
import fi.dy.masa.malilib.util.position.Vec2d;

public class InputEventHandler implements IKeybindManager, IInputManager
{
    private static final InputEventHandler INSTANCE = new InputEventHandler();

    private final Multimap<Integer, IKeybind> hotkeyMap = ArrayListMultimap.create();
    private final List<KeybindCategory> allKeybinds = new ArrayList<>();
    private final List<IKeybindProvider> keybindProviders = new ArrayList<>();
    private final List<IKeyboardInputHandler> keyboardHandlers = new ArrayList<>();
    private final List<IMouseInputHandler> mouseHandlers = new ArrayList<>();
    private final Set<IKeybind> pendingReleaseKeybinds = new HashSet<>();
    private double mouseWheelDeltaSum;
    private KeyState lastKeyState;
    private int lastAction;
    private int lastScanCode;
    private Vec2d lastMousePos;

    private InputEventHandler() { }

    public static IKeybindManager getKeybindManager()
    {
        return INSTANCE;
    }

    public static IInputManager getInputManager()
    {
        return INSTANCE;
    }

    @Override
    public void registerKeybindProvider(IKeybindProvider provider)
    {
        if (this.keybindProviders.contains(provider) == false)
        {
            this.keybindProviders.add(provider);
        }

        provider.addHotkeys(this);
    }

    @Override
    public void unregisterKeybindProvider(IKeybindProvider provider)
    {
        this.keybindProviders.remove(provider);
    }

    @Override
    public List<KeybindCategory> getKeybindCategories()
    {
        return this.allKeybinds;
    }

    @Override
    public void updateUsedKeys()
    {
        this.hotkeyMap.clear();
        this.pendingReleaseKeybinds.clear();

        for (IKeybindProvider handler : this.keybindProviders)
        {
            handler.addKeysToMap(this);
        }
    }

    @Override
    public void addKeybindToMap(IKeybind keybind)
    {
        Collection<Integer> keys = keybind.getKeys();

        for (int key : keys)
        {
            this.hotkeyMap.put(key, keybind);
        }
    }

    @Override
    public void addHotkeysForCategory(String modName, String keyCategory, List<? extends IHotkey> hotkeys)
    {
        KeybindCategory cat = new KeybindCategory(modName, keyCategory, hotkeys);

        // Remove a previous entry, if any (matched based on the modName and keyCategory only!)
        this.allKeybinds.remove(cat);
        this.allKeybinds.add(cat);
    }

    @Override
    public void registerKeyboardInputHandler(IKeyboardInputHandler handler)
    {
        if (this.keyboardHandlers.contains(handler) == false)
        {
            this.keyboardHandlers.add(handler);
        }
    }

    @Override
    public void unregisterKeyboardInputHandler(IKeyboardInputHandler handler)
    {
        this.keyboardHandlers.remove(handler);
    }

    @Override
    public void registerMouseInputHandler(IMouseInputHandler handler)
    {
        if (this.mouseHandlers.contains(handler) == false)
        {
            this.mouseHandlers.add(handler);
        }
    }

    @Override
    public void unregisterMouseInputHandler(IMouseInputHandler handler)
    {
        this.mouseHandlers.remove(handler);
    }

    @Nullable
    public KeyState getLastKeyState()
    {
        return this.lastKeyState;
    }

    public int getLastScanCode()
    {
        return this.lastScanCode;
    }

    public int getLastActionCode()
    {
        return this.lastAction;
    }

    private void setLastMousePos(final double mouseX, final double mouseY)
    {
        this.lastMousePos = new Vec2d(mouseX, mouseY);
    }

    public Vec2d getLastMousePos()
    {
        return this.lastMousePos;
    }

    @ApiStatus.Internal
    public boolean onHandleEvent(final SDL_Event event)
    {
        boolean cancel = false;

        this.lastAction = ActionCodes.NONE;
        this.lastScanCode = ScanCodes.SCAN_UNKNOWN;
        this.lastKeyState = KeyState.fromEventType(event.type());

        switch (event.type())
        {
            case EventCodes.EVENT_KEY_PRESS ->
            {
                SDL_KeyboardEvent keyEvent = event.key();
                this.lastAction = keyEvent.repeat() ? ActionCodes.REPEAT : ActionCodes.PRESSED;
                this.lastScanCode = keyEvent.scancode();

                // Update the cached pressed keys status
                KeybindMulti.onKeyInputPre(new KeyEvent(keyEvent.scancode(), keyEvent.key(), keyEvent.mod()), this.lastAction);
            }
            case EventCodes.EVENT_KEY_RELEASE ->
            {
                SDL_KeyboardEvent keyEvent = event.key();
                this.lastAction = ActionCodes.RELEASED;
                this.lastScanCode = keyEvent.scancode();

                // Update the cached pressed keys status
                KeybindMulti.onKeyInputPre(new KeyEvent(keyEvent.scancode(), keyEvent.key(), keyEvent.mod()), ActionCodes.RELEASED);
            }
            case EventCodes.EVENT_MOUSE_PRESS ->
            {
                SDL_MouseButtonEvent buttonEvent = event.button();
                this.lastAction = ActionCodes.PRESSED;
                this.lastScanCode = buttonEvent.button() - ScanCodes.OFFSET_MOUSE;
                this.setLastMousePos(buttonEvent.x(), buttonEvent.y());

                // Update the cached pressed keys status
                KeybindMulti.onKeyInputPre(new KeyEvent(buttonEvent.button() - ScanCodes.OFFSET_MOUSE, KeyCodes.KEY_UNKNOWN, SDLKeyboard.SDL_GetModState()), ActionCodes.PRESSED);
            }
            case EventCodes.EVENT_MOUSE_RELEASE ->
            {
                SDL_MouseButtonEvent buttonEvent = event.button();
                this.lastAction = ActionCodes.RELEASED;
                this.lastScanCode = buttonEvent.button() - ScanCodes.OFFSET_MOUSE;
                this.setLastMousePos(buttonEvent.x(), buttonEvent.y());

                // Update the cached pressed keys status
                KeybindMulti.onKeyInputPre(new KeyEvent(buttonEvent.button() - ScanCodes.OFFSET_MOUSE, KeyCodes.KEY_UNKNOWN, SDLKeyboard.SDL_GetModState()), ActionCodes.RELEASED);
            }
            case EventCodes.EVENT_GAMEPAD_ADDED -> GamepadManager.INSTANCE.onGamepadAdded(event);
            case EventCodes.EVENT_GAMEPAD_REMOVED -> GamepadManager.INSTANCE.onGamepadRemoved(event);
            case EventCodes.EVENT_GAMEPAD_REMAPPED -> GamepadManager.INSTANCE.onGamepadRemapped(event);
            case EventCodes.EVENT_GAMEPAD_BUTTON_DOWN, EventCodes.EVENT_GAMEPAD_BUTTON_UP ->
                    cancel = GamepadManager.INSTANCE.onGamepadButton(event);
            case EventCodes.EVENT_GAMEPAD_AXIS_MOTION ->
                    cancel = GamepadManager.INSTANCE.onGamepadAxisMotion(event);
	        default ->
	        {
                this.lastAction = ActionCodes.NONE;
                this.lastScanCode = ScanCodes.SCAN_UNKNOWN;
            }
        }

//        if (this.lastKeyState != null &&
//            (this.lastKeyState != KeyState.MOUSE_MOVED &&
//             this.lastKeyState != KeyState.GAMEPAD_UPDATE_COMPLETE &&
//             this.lastKeyState != KeyState.GAMEPAD_AXIS_MOTION))
//        {
//            MaLiLib.LOGGER.warn("onHandleEvent(): state: {}, scanCode: {}",
//                                this.lastKeyState.toString(),
//                                this.lastScanCode);
//        }

        // Cancel further processing -> true
        return cancel;
    }

    @ApiStatus.Internal
    public void onHandleKeymapChange()
    {
        if (MaLiLibReference.EXPERIMENTAL_MODE)
        {
            // Attempt AZERTY Detection
            boolean isAzerty = InputUtils.isAzertyLayout();

            if (isAzerty && MaLiLibConfigs.Generic.KEYBOARD_TYPE.getOptionListValue() != KeyboardType.AZERTY)
            {
                MaLiLibConfigs.Generic.KEYBOARD_TYPE.setOptionListValue(KeyboardType.AZERTY);
                MaLiLib.LOGGER.warn("onHandleKeymapChange(): KEYBOARD_TYPE --> AZERTY");
            }
            else if (!isAzerty && MaLiLibConfigs.Generic.KEYBOARD_TYPE.getOptionListValue() != KeyboardType.QWERTY)
            {
                MaLiLibConfigs.Generic.KEYBOARD_TYPE.setOptionListValue(KeyboardType.QWERTY);
                MaLiLib.LOGGER.warn("onHandleKeymapChange(): KEYBOARD_TYPE --> QWERTY");
            }
        }
    }

    @ApiStatus.Internal
    public void onHandleDropStart()
    {
        // TODO
//        MaLiLib.LOGGER.warn("onHandleDropStart():{}:", Thread.currentThread().getName());
    }

    @ApiStatus.Internal
    public boolean onKeyInput(KeyEvent input, int action, @Nonnull Minecraft mc)
    {
//        // Update the cached pressed keys status
//        KeybindMulti.onKeyInputPre(input, action);

        boolean eventKeyState = action != ActionCodes.RELEASED;
        int scanCode = input.key();

        if (scanCode == ScanCodes.SCAN_ESCAPE)
        {
            this.pendingReleaseKeybinds.clear();
        }

        boolean cancel;

        if (eventKeyState)
        {
            cancel = this.checkKeyBindsForChanges(scanCode);
        }
        else
        {
            cancel = this.checkKeyBindsForChanges(scanCode);

            // Trigger any pending prefix keybinds that were waiting for release
            // (e.g. M pressed alone for menu, without pressing the second key of M+T)
            List<IKeybind> toTrigger = new ArrayList<>();
            for (IKeybind keybind : this.pendingReleaseKeybinds)
            {
                if (keybind.getKeys().contains(scanCode))
                {
                    toTrigger.add(keybind);
                }
            }

            for (IKeybind keybind : toTrigger)
            {
                this.pendingReleaseKeybinds.remove(keybind);

                if (this.isKeybindContextValid(keybind))
                {
                    KeyAction actionToTrigger = keybind.getSettings().getActivateOn() == KeyAction.RELEASE ? KeyAction.RELEASE : KeyAction.PRESS;
                    keybind.triggerAction(actionToTrigger);
                    cancel |= keybind.getSettings().shouldCancel();
                }
            }
        }

        if (this.keyboardHandlers.isEmpty() == false)
        {
            for (IKeyboardInputHandler handler : this.keyboardHandlers)
            {
                if (handler.onKeyInput(input, eventKeyState))
                {
                    this.printInputCancellationDebugMessage(handler);
                    return true;
                }
            }
        }

        return cancel;
    }

    @ApiStatus.Internal
    public boolean onGamepadButton(GamepadButtonEvent input)
    {
        final int scanCode = input.scanCode() - ScanCodes.OFFSET_GAMEPAD;
        final int action = input.action();
        // Wrap it into a KeyEvent
        KeyEvent key = new KeyEvent(scanCode, KeyCodes.KEY_UNKNOWN, KeyCodes.KMOD_NONE);
        Minecraft mc = Minecraft.getInstance();

        this.lastAction = action;
        this.lastScanCode = scanCode;

        // Update the cached pressed keys status
        KeybindMulti.onKeyInputPre(key, action);

        // Handle GUI clicks
        if (mc.gui.screen() != null && mc.gui.screen() instanceof GuiBase gui)
        {
            if (gui.keyPressed(key))
            {
                this.printInputCancellationDebugMessage(InputEventHandler.class);
                return true;
            }
        }

        return this.onKeyInput(key, action, mc);
    }

    @ApiStatus.Internal
    public boolean onGamepadAxisMotion(GamepadButtonEvent input, final short amount)
    {
        final int scanCode = input.scanCode() - ScanCodes.OFFSET_GAMEPAD_AXIS;
        final int action = input.action();
        // Wrap it into a KeyEvent
        KeyEvent key = new KeyEvent(scanCode, KeyCodes.KEY_UNKNOWN, KeyCodes.KMOD_NONE);
        Minecraft mc = Minecraft.getInstance();

        this.lastAction = action;
        this.lastScanCode = scanCode;

        // Update the cached pressed keys status
        KeybindMulti.onKeyInputPre(key, action);

        // Handle GUI clicks
        if (mc.gui.screen() != null && mc.gui.screen() instanceof GuiBase gui)
        {
            if (gui.keyPressed(key))
            {
                this.printInputCancellationDebugMessage(InputEventHandler.class);
                return true;
            }
        }
        return this.onKeyInput(key, action, mc);
    }

    @ApiStatus.Internal
    public boolean onMouseClick(MouseButtonEvent click, int action)
    {
        boolean cancel = false;
        this.setLastMousePos(click.x(), click.y());

        if (click.input() != ScanCodes.SCAN_UNKNOWN)
        {
            boolean eventButtonState = action == ActionCodes.PRESSED;

//            // Update the cached pressed keys status
//			KeybindMulti.onKeyInputPre(new KeyEvent(click.input() - ScanCodes.OFFSET_MOUSE, KeyCodes.KEY_UNKNOWN, KeyCodes.KMOD_NONE), action);

            cancel = this.checkKeyBindsForChanges(click.input() - ScanCodes.OFFSET_MOUSE);

            if (this.mouseHandlers.isEmpty() == false)
            {
                for (IMouseInputHandler handler : this.mouseHandlers)
                {
                    if (handler.onMouseClick(click, eventButtonState))
                    {
                        this.printInputCancellationDebugMessage(handler);
                        return true;
                    }
                }
            }
        }

        return cancel;
    }

    private void printInputCancellationDebugMessage(Object handler)
    {
        if (MaLiLibConfigs.Debug.INPUT_CANCELLATION_DEBUG.getBooleanValue())
        {
            String msg = String.format("Cancel requested by input handler '%s'", handler.getClass().getName());
            InfoUtils.showInGameMessage(Message.MessageType.INFO, msg);
            MaLiLib.LOGGER.info(msg);
        }
    }

    @ApiStatus.Internal
    public boolean onMouseScroll(final double mouseX, final double mouseY, final double xOffset, final double yOffset)
    {
        Minecraft mc = Minecraft.getInstance();
        boolean discrete = mc.options.discreteMouseScroll().get();
        double sensitivity = mc.options.mouseWheelSensitivity().get();
        double amount = (discrete ? Math.signum(yOffset) : yOffset) * sensitivity;

        this.setLastMousePos(mouseX, mouseY);

        if (MaLiLibConfigs.Debug.MOUSE_SCROLL_DEBUG.getBooleanValue())
        {
            int time = (int) (System.currentTimeMillis() & 0xFFFF);
            int tick = mc.level != null ? (int) (mc.level.getGameTime() & 0xFFFF) : 0;
            String timeStr = String.format("time: %04X, tick: %04X", time, tick);
            MaLiLib.LOGGER.info("{} - xOffset: {}, yOffset: {}, discrete: {}, sensitivity: {}, amount: {}",
                                timeStr, xOffset, yOffset, discrete, sensitivity, amount);
        }

        if (amount != 0 && this.mouseHandlers.isEmpty() == false)
        {
            if (this.mouseWheelDeltaSum != 0.0 && Math.signum(amount) != Math.signum(this.mouseWheelDeltaSum))
            {
                this.mouseWheelDeltaSum = 0.0;
            }

            this.mouseWheelDeltaSum += amount;
            amount = (int) this.mouseWheelDeltaSum;

            if (amount != 0.0)
            {
                this.mouseWheelDeltaSum -= amount;

                for (IMouseInputHandler handler : this.mouseHandlers)
                {
                    if (handler.onMouseScroll(mouseX, mouseY, amount))
                    {
                        this.printInputCancellationDebugMessage(handler);
                        return true;
                    }
                }
            }
        }

        return false;
    }

    @ApiStatus.Internal
    public void onMouseMove(final double mouseX, final double mouseY)
    {
        this.setLastMousePos(mouseX, mouseY);

        if (this.mouseHandlers.isEmpty() == false)
        {
            for (IMouseInputHandler handler : this.mouseHandlers)
            {
                handler.onMouseMove(mouseX, mouseY);
            }
        }
    }

    @ApiStatus.Internal
    public boolean onMouseDrop(final double mouseX, final double mouseY, final List<String> filePaths)
    {
        this.setLastMousePos(mouseX, mouseY);

        if (this.mouseHandlers.isEmpty() == false)
        {
            List<Path> paths = new ArrayList<>();

            for (String entry : filePaths)
            {
                try
                {
                    final String sanitizedFile = FileNameUtils.generateSafeFileName(FileNameUtils.generateSimpleUnicodeSafeFileName(entry));
                    Path path = Paths.get(sanitizedFile).normalize();

                    if (Files.exists(path) && Files.isReadable(path) &&
                        Files.isRegularFile(path))
                    {
                        paths.add(path);
                    }
                }
                catch (InvalidPathException e)
                {
                    MaLiLib.LOGGER.error("Exception; not a valid path '{}'; {}", entry, e.getMessage());
                }
            }

            for (IMouseInputHandler handler : this.mouseHandlers)
            {
                FileFilter filter = handler.dropFileFilter();
                List<Path> filteredPaths = new ArrayList<>();

                if (filter != null)
                {
                    for (Path path : paths)
                    {
                        if (filter.accept(path.toFile()))
                        {
                            filteredPaths.add(path);
                        }
                    }
                }
                else
                {
                    filteredPaths.addAll(paths);
                }

                if (handler.onMouseFilesDrop(mouseX, mouseY, filteredPaths))
                {
                    this.printInputCancellationDebugMessage(handler);
                    return true;
                }
            }
        }

        return false;
    }

    public boolean isKeybindContextValid(IKeybind keybind)
    {
        KeybindSettings.Context context = keybind.getSettings().getContext();
        if (context == KeybindSettings.Context.ANY)
        {
            return true;
        }

        boolean inGame = GuiUtils.getCurrentScreen() == null;
        return (context == KeybindSettings.Context.INGAME) == inGame;
    }

    private boolean checkKeyBindsForChanges(int eventKey)
    {
        boolean cancel = false;
        Collection<IKeybind> keybinds = this.hotkeyMap.get(eventKey);

        if (keybinds.isEmpty() == false)
        {
            // Sort keybinds longer first so chords (e.g. M+T) have higher priority than single-key subsets (e.g. M)
            List<IKeybind> list = new ArrayList<>(keybinds);
            list.sort((a, b) -> Integer.compare(b.getKeys().size(), a.getKeys().size()));

            KeybindMulti.resetTriggeredCount();
            List<IKeybind> triggeredKeybinds = new ArrayList<>();

            for (IKeybind keybind : list)
            {
                if (!this.isKeybindContextValid(keybind))
                {
                    // Still update unpressed state if needed without triggering or deferring
                    keybind.updateIsPressed(false);
                    continue;
                }

                // If a longer chord triggered in this same turn, silently unpress any subset keybinds
                // so they don't fire alongside the chord.
                boolean isSubsetOfTriggered = false;
                for (IKeybind triggered : triggeredKeybinds)
                {
                    if (triggered.getKeys().size() > keybind.getKeys().size() &&
                        triggered.getKeys().containsAll(keybind.getKeys()))
                    {
                        isSubsetOfTriggered = true;
                        break;
                    }
                }

                if (isSubsetOfTriggered)
                {
                    keybind.unpress();
                    continue;
                }

                // If this is a regular keyboard key that serves as a prefix for a longer registered chord (e.g. 'M' in 'M+T'),
                // defer triggering until the key is released so the user has time to type the full combination.
                if (eventKey >= 0 &&
                    !ScanCodes.isModifierKey(eventKey) &&
                    !InputUtils.isVanillaKeyBound(eventKey) &&
                    KeybindMulti.isPressedKey(eventKey) &&
                    this.hasPrefixKeybinds(keybind))
                {
                    this.pendingReleaseKeybinds.add(keybind);
                    cancel |= keybind.getSettings().shouldCancel();
                    continue;
                }

                // Note: isPressed() has to get called for key releases too, to reset the state
                boolean wasCancel = keybind.updateIsPressed();
                cancel |= wasCancel;

                if (keybind.isKeybindHeld())
                {
                    triggeredKeybinds.add(keybind);

                    // If a multi-key chord triggered (e.g. M+T), silently unpress any subset keybinds (e.g. M),
                    // and remove any pending release triggers for subset keybinds
                    if (keybind.getKeys().size() > 1)
                    {
                        for (int k : keybind.getKeys())
                        {
                            for (IKeybind subset : this.hotkeyMap.get(k))
                            {
                                if (subset != keybind &&
                                    subset.getKeys().size() < keybind.getKeys().size() &&
                                    keybind.getKeys().containsAll(subset.getKeys()))
                                {
                                    subset.unpress();
                                    this.pendingReleaseKeybinds.remove(subset);
                                }
                            }
                        }

                        this.pendingReleaseKeybinds.removeIf(pending ->
                            pending.getKeys().size() < keybind.getKeys().size() &&
                            keybind.getKeys().containsAll(pending.getKeys())
                        );
                    }
                }
            }
        }

        return cancel;
    }

    /**
     * Checks if this keybind is a strict prefix of a longer registered keybind in the same context.
     * Only non-modifier keys that are not bound to vanilla controls participate in prefix chords.
     */
    public boolean hasPrefixKeybinds(IKeybind keybind)
    {
        List<Integer> keys = keybind.getKeys();
        if (keys.isEmpty())
        {
            return false;
        }

        // Only keyboard non-modifier keys that are not bound to vanilla actions participate in prefix chords
        for (int k : keys)
        {
            if (k < 0 || ScanCodes.isModifierKey(k) || InputUtils.isVanillaKeyBound(k))
            {
                return false;
            }
        }

        if (!this.isKeybindContextValid(keybind))
        {
            return false;
        }

        int firstKey = keys.getFirst();
        Collection<IKeybind> candidates = this.hotkeyMap.get(firstKey);

        for (IKeybind other : candidates)
        {
            if (other != keybind && other.getKeys().size() > keys.size() && this.isKeybindContextValid(other))
            {
                List<Integer> otherKeys = other.getKeys();
                if (other.getSettings().isOrderSensitive())
                {
                    if (otherKeys.size() >= keys.size() && otherKeys.subList(0, keys.size()).equals(keys))
                    {
                        return true;
                    }
                }
                else if (otherKeys.containsAll(keys))
                {
                    return true;
                }
            }
        }

        return false;
    }


}
