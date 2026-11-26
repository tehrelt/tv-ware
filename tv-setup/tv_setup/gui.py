"""CustomTkinter graphite desktop UI for tv-setup."""

from __future__ import annotations

import ipaddress
import queue
import re
import threading
import tkinter as tk
from collections.abc import Callable
from pathlib import Path
from tkinter import filedialog, messagebox

import customtkinter as ctk

from .adb_client import (
    DEFAULT_TOOLS_DIR,
    AdbClient,
    AdbError,
    download_platform_tools,
    find_adb_executable,
)
from .discovery import DeviceStatus, DiscoveredDevice, browse_mdns, discover_devices
from .installer import (
    LocalApk,
    SignatureConflictError,
    download_repo_apks,
    import_local_apk,
    install_apk,
    reinstall_after_uninstall,
    scan_local_apks,
)
from .optimizer import ApplyPlan, DeviceInspection, apply_optimizations, inspect_device, rollback_device
from .presets import BLOAT_CATALOG

BASE_DIR = Path(__file__).resolve().parents[1]
DEFAULT_APKS_DIR = BASE_DIR / "apks"
DEFAULT_STATE_DIR = BASE_DIR / "state"

PALETTE: dict[str, str] = {
    "bg": "#0A0A0B",
    "card": "#1C1C1E",
    "surface": "#2C2C2E",
    "surface_hover": "#3A3A3C",
    "text": "#F5F5F7",
    "muted": "#A1A1A6",
    "accent": "#0A84FF",
    "accent_hover": "#0070E0",
    "success": "#4CC38A",
    "warning": "#FF9F0A",
    "danger": "#FF453A",
    "border": "#38383A",
}

_STATUS_LABELS: dict[DeviceStatus, str] = {
    DeviceStatus.CONNECTED: "Подключено",
    DeviceStatus.READY: "ADB готов",
    DeviceStatus.NEEDS_PAIRING: "Нужно сопряжение",
    DeviceStatus.UNAUTHORIZED: "Не авторизован",
    DeviceStatus.DEBUG_OFF: "Отладка выкл.",
}

_BTN_ACCENT = {"fg_color": PALETTE["accent"], "hover_color": PALETTE["accent_hover"], "text_color": PALETTE["text"]}
_BTN_SURFACE = {"fg_color": PALETTE["surface"], "hover_color": PALETTE["surface_hover"], "text_color": PALETTE["text"]}
_ENTRY_STYLE = {"fg_color": PALETTE["surface"], "border_color": PALETTE["border"], "text_color": PALETTE["text"]}
_CHECKBOX_STYLE = {
    "fg_color": PALETTE["accent"],
    "hover_color": PALETTE["accent_hover"],
    "border_color": PALETTE["border"],
    "checkmark_color": PALETTE["text"],
}
_SCROLL_STYLE = {
    "fg_color": PALETTE["bg"],
    "scrollbar_button_color": PALETTE["surface"],
    "scrollbar_button_hover_color": PALETTE["surface_hover"],
    "corner_radius": 8,
}

_HOSTNAME_RE = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$")
_NO_DEVICES_LABEL = "Устройства не найдены"


def format_device_label(device: DiscoveredDevice) -> str:
    if device.ip:
        port = device.port if device.port is not None else device.pairing_port
        endpoint = f"{device.ip}:{port}" if port is not None else device.ip
    else:
        endpoint = device.serial or device.id
    parts = [endpoint]
    if device.name and device.name not in {endpoint, device.ip}:
        parts.append(device.name)
    parts.append(_STATUS_LABELS[device.status])
    return " · ".join(parts)


def parse_port(raw: str) -> int:
    text = raw.strip()
    if not text.isascii() or not text.isdecimal():
        raise ValueError(f"Некорректный порт: {raw!r}")
    port = int(text)
    if not 1 <= port <= 65535:
        raise ValueError(f"Порт вне диапазона 1..65535: {port}")
    return port


def parse_manual_target(raw: str, default_port: int = 5555) -> tuple[str, int, bool]:
    text = raw.strip()
    if not text or any(ch.isspace() for ch in text):
        raise ValueError("Укажите IP или IP:порт без пробелов")
    if ":" in text:
        host, _, raw_port = text.rpartition(":")
        port, has_explicit_port = parse_port(raw_port), True
    else:
        host, port, has_explicit_port = text, default_port, False
    if not host:
        raise ValueError("Не указан IP-адрес или хост")
    try:
        ipaddress.IPv4Address(host)
    except ipaddress.AddressValueError:
        if not _HOSTNAME_RE.fullmatch(host):
            raise ValueError(f"Некорректный адрес: {host!r}") from None
    return host, port, has_explicit_port


def _add_labeled_entry(parent: ctk.CTkFrame, label: str, placeholder: str, color: str = PALETTE["muted"]) -> ctk.CTkEntry:
    ctk.CTkLabel(parent, text=label, text_color=color, font=ctk.CTkFont(size=12)).pack(anchor="w", padx=14)
    entry = ctk.CTkEntry(parent, placeholder_text=placeholder, **_ENTRY_STYLE)
    entry.pack(fill="x", padx=14, pady=(4, 8))
    return entry


def _add_dialog_buttons(parent: ctk.CTkFrame, submit_text: str, on_submit: Callable[[], None], on_cancel: Callable[[], None]) -> None:
    row = ctk.CTkFrame(parent, fg_color="transparent")
    row.pack(fill="x", padx=14, pady=(6, 12))
    ctk.CTkButton(row, text=submit_text, command=on_submit, **_BTN_ACCENT).pack(side="left", expand=True, fill="x", padx=(0, 6))
    ctk.CTkButton(row, text="Отмена", width=90, command=on_cancel, **_BTN_SURFACE).pack(side="right")


class _PairingDialog(ctk.CTkToplevel):
    def __init__(self, parent: TvSetupApp, device: DiscoveredDevice, on_submit: Callable[[str, int | None], None]) -> None:
        super().__init__(parent, fg_color=PALETTE["bg"])
        self._on_submit = on_submit
        self.title("Сопряжение по коду")
        self.geometry("380x250")
        self.resizable(False, False)
        self.transient(parent)
        self.grab_set()

        card = ctk.CTkFrame(self, fg_color=PALETTE["card"], corner_radius=12)
        card.pack(fill="both", expand=True, padx=14, pady=14)
        ctk.CTkLabel(
            card, text=f"Устройство: {device.ip}:{device.pairing_port}",
            text_color=PALETTE["text"], font=ctk.CTkFont(size=14, weight="bold"),
        ).pack(anchor="w", padx=14, pady=(12, 6))

        self._code_entry = _add_labeled_entry(card, "Введите 6-значный код с экрана ТВ:", "123456")
        self._port_entry = _add_labeled_entry(card, "Порт подключения (опционально, иначе автопоиск):", "например 41234")
        if device.port is not None:
            self._port_entry.insert(0, str(device.port))

        self._error_label = ctk.CTkLabel(card, text="", text_color=PALETTE["danger"], font=ctk.CTkFont(size=11))
        self._error_label.pack(anchor="w", padx=14)
        _add_dialog_buttons(card, "Сопрячь и подключить", self._submit, self.destroy)
        self._code_entry.focus_set()
        self.bind("<Return>", lambda _event: self._submit())

    def _submit(self) -> None:
        code = self._code_entry.get().strip()
        if not code:
            self._error_label.configure(text="Введите код сопряжения")
            return
        raw_port = self._port_entry.get().strip()
        try:
            connect_port = parse_port(raw_port) if raw_port else None
        except ValueError as exc:
            self._error_label.configure(text=str(exc))
            return
        self.destroy()
        self._on_submit(code, connect_port)


class _ManualIpDialog(ctk.CTkToplevel):
    def __init__(self, parent: TvSetupApp, on_submit: Callable[[str, int, bool, int | None, str], None]) -> None:
        super().__init__(parent, fg_color=PALETTE["bg"])
        self._on_submit = on_submit
        self.title("Подключение по IP")
        self.geometry("400x310")
        self.resizable(False, False)
        self.transient(parent)
        self.grab_set()

        card = ctk.CTkFrame(self, fg_color=PALETTE["card"], corner_radius=12)
        card.pack(fill="both", expand=True, padx=14, pady=14)
        ctk.CTkFrame(card, fg_color="transparent", height=8).pack()
        self._target_entry = _add_labeled_entry(card, "IP или IP:порт (по умолчанию 5555):", "192.168.31.42:5555", PALETTE["text"])
        self._pair_port_entry = _add_labeled_entry(card, "Порт сопряжения (опционально):", "например 37123")
        self._code_entry = _add_labeled_entry(card, "Код сопряжения (опционально, 6 цифр):", "123456")

        self._error_label = ctk.CTkLabel(card, text="", text_color=PALETTE["danger"], font=ctk.CTkFont(size=11))
        self._error_label.pack(anchor="w", padx=14)
        _add_dialog_buttons(card, "Подключить", self._submit, self.destroy)
        self._target_entry.focus_set()
        self.bind("<Return>", lambda _event: self._submit())

    def _submit(self) -> None:
        try:
            host, port, has_explicit_port = parse_manual_target(self._target_entry.get())
            raw_pair_port = self._pair_port_entry.get().strip()
            pair_port = parse_port(raw_pair_port) if raw_pair_port else None
        except ValueError as exc:
            self._error_label.configure(text=str(exc))
            return
        code = self._code_entry.get().strip()
        if pair_port is not None and not code:
            self._error_label.configure(text="Введите код сопряжения")
            return
        if code and pair_port is None and not has_explicit_port:
            self._error_label.configure(text="Укажите порт сопряжения")
            return
        self.destroy()
        self._on_submit(host, port, has_explicit_port, pair_port, code)


class TvSetupApp(ctk.CTk):
    def __init__(
        self,
        tools_dir: Path = DEFAULT_TOOLS_DIR,
        apks_dir: Path = DEFAULT_APKS_DIR,
        state_dir: Path = DEFAULT_STATE_DIR,
        adb_client: AdbClient | None = None,
        auto_discover: bool = True,
    ) -> None:
        ctk.set_appearance_mode("dark")
        super().__init__(fg_color=PALETTE["bg"])

        self.tools_dir = Path(tools_dir)
        self.apks_dir = Path(apks_dir)
        self.state_dir = Path(state_dir)
        self.adb = adb_client or AdbClient(tools_dir=self.tools_dir)

        self._closing = False
        self._busy = False
        self._ui_queue: queue.SimpleQueue[Callable[[], None]] = queue.SimpleQueue()
        self._pending_events: list[threading.Event] = []
        self._device_by_label: dict[str, DiscoveredDevice] = {}
        self._connected_serial: str | None = None
        self._inspection: DeviceInspection | None = None
        self._local_apks: list[LocalApk] = []
        self._apk_vars: dict[str, tk.BooleanVar] = {}
        self._bloat_vars: dict[str, tk.BooleanVar] = {}
        self._bloat_widgets: dict[str, ctk.CTkCheckBox] = {}
        self._last_progress_pct = -1

        self.title("tv-setup")
        self.geometry("980x700")
        self.minsize(860, 620)
        self.protocol("WM_DELETE_WINDOW", self._on_close)

        self._build_ui()
        self._refresh_adb_status()
        self._refresh_apk_list()
        self._refresh_inspection_ui(None)
        self.after(25, self._poll_ui_queue)
        if auto_discover:
            self.after(150, self.start_discovery)

    def _build_ui(self) -> None:
        self.grid_columnconfigure(0, weight=1)
        self.grid_rowconfigure(2, weight=1)
        self._build_top_bar()
        self._build_connection_card()
        self._build_columns()
        self._build_bottom_panel()

    def _build_top_bar(self) -> None:
        bar = ctk.CTkFrame(self, fg_color=PALETTE["card"], corner_radius=12)
        bar.grid(row=0, column=0, sticky="ew", padx=16, pady=(14, 8))
        bar.grid_columnconfigure(1, weight=1)
        ctk.CTkLabel(bar, text="tv-setup", text_color=PALETTE["text"], font=ctk.CTkFont(size=20, weight="bold")).grid(
            row=0, column=0, padx=(16, 12), pady=12, sticky="w"
        )
        right = ctk.CTkFrame(bar, fg_color="transparent")
        right.grid(row=0, column=1, padx=12, pady=10, sticky="e")

        self._adb_status_label = ctk.CTkLabel(
            right, text="ADB: проверка...", text_color=PALETTE["muted"], fg_color=PALETTE["surface"],
            corner_radius=8, padx=10, pady=4, font=ctk.CTkFont(size=12),
        )
        self._adb_status_label.pack(side="left", padx=(0, 8))
        self._download_adb_btn = ctk.CTkButton(right, text="Скачать ADB", width=115, command=self.start_download_adb, **_BTN_ACCENT)
        self._discover_btn = ctk.CTkButton(right, text="Найти ТВ", width=100, command=self.start_discovery, **_BTN_SURFACE)
        self._discover_btn.pack(side="left", padx=(0, 8))
        self._refresh_btn = ctk.CTkButton(right, text="Обновить", width=100, command=self.refresh_all, **_BTN_SURFACE)
        self._refresh_btn.pack(side="left")

    def _build_connection_card(self) -> None:
        card = ctk.CTkFrame(self, fg_color=PALETTE["card"], corner_radius=12)
        card.grid(row=1, column=0, sticky="ew", padx=16, pady=(0, 8))
        card.grid_columnconfigure(1, weight=1)
        ctk.CTkLabel(card, text="Устройства:", text_color=PALETTE["text"], font=ctk.CTkFont(size=13, weight="bold")).grid(
            row=0, column=0, padx=(16, 8), pady=(12, 6), sticky="w"
        )
        self._device_var = tk.StringVar(value=_NO_DEVICES_LABEL)
        self._device_menu = ctk.CTkOptionMenu(
            card, variable=self._device_var, values=[_NO_DEVICES_LABEL], fg_color=PALETTE["surface"],
            button_color=PALETTE["surface_hover"], button_hover_color=PALETTE["accent"],
            dropdown_fg_color=PALETTE["card"], dropdown_hover_color=PALETTE["surface"],
            text_color=PALETTE["text"], state="disabled", command=self._on_device_selected,
        )
        self._device_menu.grid(row=0, column=1, padx=(0, 8), pady=(12, 6), sticky="ew")
        self._connect_btn = ctk.CTkButton(card, text="Подключить", width=120, command=self.connect_selected_device, **_BTN_ACCENT)
        self._connect_btn.grid(row=0, column=2, padx=(0, 8), pady=(12, 6))
        self._manual_ip_btn = ctk.CTkButton(card, text="IP вручную", width=110, command=self._open_manual_ip_dialog, **_BTN_SURFACE)
        self._manual_ip_btn.grid(row=0, column=3, padx=(0, 16), pady=(12, 6))
        self._status_label = ctk.CTkLabel(card, text="Статус: Не подключено", text_color=PALETTE["muted"], font=ctk.CTkFont(size=12), anchor="w")
        self._status_label.grid(row=1, column=0, columnspan=4, padx=16, pady=(0, 12), sticky="ew")

    def _build_columns(self) -> None:
        container = ctk.CTkFrame(self, fg_color="transparent")
        container.grid(row=2, column=0, sticky="nsew", padx=16, pady=(0, 8))
        container.grid_columnconfigure((0, 1), weight=1, uniform="cols")
        container.grid_rowconfigure(0, weight=1)
        self._build_apk_column(container)
        self._build_optimize_column(container)

    def _build_apk_column(self, container: ctk.CTkFrame) -> None:
        left = ctk.CTkFrame(container, fg_color=PALETTE["card"], corner_radius=12)
        left.grid(row=0, column=0, sticky="nsew", padx=(0, 6))
        left.grid_columnconfigure(0, weight=1)
        left.grid_rowconfigure(1, weight=1)
        ctk.CTkLabel(left, text="Приложения (tv-setup/apks/)", text_color=PALETTE["text"], font=ctk.CTkFont(size=14, weight="bold")).grid(
            row=0, column=0, padx=14, pady=(12, 6), sticky="w"
        )
        self._apk_scroll = ctk.CTkScrollableFrame(left, **_SCROLL_STYLE)
        self._apk_scroll.grid(row=1, column=0, sticky="nsew", padx=12, pady=(0, 10))

        actions = ctk.CTkFrame(left, fg_color="transparent")
        actions.grid(row=2, column=0, sticky="ew", padx=12, pady=(0, 12))
        actions.grid_columnconfigure((0, 1), weight=1)
        self._download_apks_btn = ctk.CTkButton(actions, text="Скачать актуальные", command=self.start_download_apks, **_BTN_SURFACE)
        self._download_apks_btn.grid(row=0, column=0, sticky="ew", padx=(0, 6))
        self._add_apk_btn = ctk.CTkButton(actions, text="Добавить APK...", command=self.add_local_apk, **_BTN_SURFACE)
        self._add_apk_btn.grid(row=0, column=1, sticky="ew", padx=(6, 0))

    def _build_optimize_column(self, container: ctk.CTkFrame) -> None:
        right = ctk.CTkFrame(container, fg_color=PALETTE["card"], corner_radius=12)
        right.grid(row=0, column=1, sticky="nsew", padx=(6, 0))
        right.grid_columnconfigure(0, weight=1)
        right.grid_rowconfigure(3, weight=1)
        ctk.CTkLabel(right, text="Оптимизация и очистка", text_color=PALETTE["text"], font=ctk.CTkFont(size=14, weight="bold")).grid(
            row=0, column=0, padx=14, pady=(12, 6), sticky="w"
        )
        toggles = ctk.CTkFrame(right, fg_color="transparent")
        toggles.grid(row=1, column=0, sticky="ew", padx=14, pady=(0, 8))

        self._fast_anim_var = tk.BooleanVar(value=True)
        self._whitelist_happ_var = tk.BooleanVar(value=True)
        self._set_home_var = tk.BooleanVar(value=True)
        self._disable_stock_var = tk.BooleanVar(value=True)
        for text, var in (
            ("Анимации 0.25x", self._fast_anim_var),
            ("Happ без энергосбережения", self._whitelist_happ_var),
            ("Сделать tv-launcher домашним экраном", self._set_home_var),
            ("Отключить стоковый лаунчер", self._disable_stock_var),
        ):
            ctk.CTkCheckBox(toggles, text=text, variable=var, text_color=PALETTE["text"], **_CHECKBOX_STYLE).pack(anchor="w", pady=3)

        ctk.CTkLabel(right, text="Найдено лишнее на ТВ:", text_color=PALETTE["muted"], font=ctk.CTkFont(size=12, weight="bold")).grid(
            row=2, column=0, padx=14, pady=(2, 4), sticky="w"
        )
        self._bloat_scroll = ctk.CTkScrollableFrame(right, **_SCROLL_STYLE)
        self._bloat_scroll.grid(row=3, column=0, sticky="nsew", padx=12, pady=(0, 12))

    def _build_bottom_panel(self) -> None:
        bottom = ctk.CTkFrame(self, fg_color=PALETTE["card"], corner_radius=12)
        bottom.grid(row=3, column=0, sticky="ew", padx=16, pady=(0, 14))
        bottom.grid_columnconfigure(0, weight=1)

        actions = ctk.CTkFrame(bottom, fg_color="transparent")
        actions.grid(row=0, column=0, sticky="ew", padx=12, pady=(10, 6))
        actions.grid_columnconfigure(2, weight=1)
        self._apply_btn = ctk.CTkButton(
            actions, text="Применить выбранное", width=180, font=ctk.CTkFont(size=13, weight="bold"),
            command=self.start_apply, **_BTN_ACCENT,
        )
        self._apply_btn.grid(row=0, column=0, padx=(0, 8))
        self._rollback_btn = ctk.CTkButton(actions, text="Откатить изменения", width=170, command=self.start_rollback, **_BTN_SURFACE)
        self._rollback_btn.grid(row=0, column=1, padx=(0, 12))
        self._progress_bar = ctk.CTkProgressBar(actions, fg_color=PALETTE["surface"], progress_color=PALETTE["accent"], height=8)
        self._progress_bar.set(0)
        self._progress_bar.grid(row=0, column=2, sticky="ew")

        self._log_box = ctk.CTkTextbox(
            bottom, height=120, fg_color=PALETTE["bg"], text_color=PALETTE["text"],
            border_color=PALETTE["border"], border_width=1, font=ctk.CTkFont(family="Consolas", size=12), wrap="word",
        )
        self._log_box.grid(row=1, column=0, sticky="ew", padx=12, pady=(0, 12))
        self._log_box.configure(state="disabled")

    def destroy(self) -> None:
        self._closing = True
        for event in list(self._pending_events):
            event.set()
        try:
            for after_id in self.tk.call("after", "info"):
                self.tk.call("after", "cancel", after_id)
        except tk.TclError:
            pass
        super().destroy()

    def _on_close(self) -> None:
        self.destroy()

    def _drain_ui_queue(self) -> None:
        if self._closing or not self.winfo_exists():
            return
        while True:
            try:
                fn = self._ui_queue.get_nowait()
            except queue.Empty:
                break
            if self._closing or not self.winfo_exists():
                break
            fn()

    def _poll_ui_queue(self) -> None:
        if self._closing:
            return
        self._drain_ui_queue()
        if not self._closing and self.winfo_exists():
            self.after(25, self._poll_ui_queue)

    def _ui_call(self, fn: Callable[[], None]) -> None:
        if self._closing:
            return
        if threading.current_thread() is threading.main_thread():
            if self.winfo_exists():
                fn()
            return
        self._ui_queue.put(fn)
        try:
            self.after(0, self._drain_ui_queue)
        except (RuntimeError, tk.TclError):
            pass

    def _append_log(self, message: str) -> None:
        self._log_box.configure(state="normal")
        self._log_box.insert("end", f"> {message}\n")
        self._log_box.see("end")
        self._log_box.configure(state="disabled")

    def _log(self, message: str) -> None:
        self._ui_call(lambda m=message: self._append_log(m))

    def _ask_yes_no_sync(self, title: str, message: str) -> bool:
        if threading.current_thread() is threading.main_thread():
            return bool(messagebox.askyesno(title, message, parent=self))
        event = threading.Event()
        self._pending_events.append(event)
        result = [False]

        def prompt() -> None:
            try:
                if not self._closing and self.winfo_exists():
                    result[0] = bool(messagebox.askyesno(title, message, parent=self))
            finally:
                event.set()

        self._ui_call(prompt)
        event.wait()
        if event in self._pending_events:
            self._pending_events.remove(event)
        return result[0]

    def _set_busy(self, busy: bool) -> None:
        self._busy = busy
        state = "disabled" if busy else "normal"
        for btn in (
            self._download_adb_btn, self._discover_btn, self._refresh_btn, self._connect_btn,
            self._manual_ip_btn, self._download_apks_btn, self._add_apk_btn, self._apply_btn, self._rollback_btn,
        ):
            btn.configure(state=state)
        if not busy:
            self._last_progress_pct = -1
            self._progress_bar.set(0)

    def _start_worker(self, target: Callable[[], None]) -> bool:
        if self._busy:
            self._log("Дождитесь завершения текущей операции")
            return False
        self._set_busy(True)

        def runner() -> None:
            try:
                target()
            except Exception as exc:
                self._log(f"Ошибка: {exc}")
            finally:
                self._ui_call(lambda: self._set_busy(False))

        threading.Thread(target=runner, daemon=True).start()
        return True

    def _update_progress(self, done: int, total: int) -> None:
        if total <= 0:
            return
        pct = min(100, int(done * 100 / total))
        if pct == self._last_progress_pct or (self._last_progress_pct <= pct < 100 and pct - self._last_progress_pct < 2):
            return
        self._last_progress_pct = pct
        self._ui_call(lambda value=pct / 100.0: self._progress_bar.set(value))

    def _refresh_adb_status(self) -> None:
        found = self.adb.adb_path if self.adb.adb_path and self.adb.adb_path.is_file() else find_adb_executable(self.tools_dir)
        if found is not None:
            self.adb.adb_path = found
            self._adb_status_label.configure(text="ADB: готов", text_color=PALETTE["success"])
            if self._download_adb_btn.winfo_manager():
                self._download_adb_btn.pack_forget()
        else:
            self._adb_status_label.configure(text="ADB: не найден", text_color=PALETTE["warning"])
            if not self._download_adb_btn.winfo_manager():
                self._download_adb_btn.pack(side="left", padx=(0, 8), before=self._discover_btn)
            self._log("ADB не найден. Нажмите «Скачать ADB» для загрузки platform-tools.")

    def _refresh_apk_list(self, select_filenames: set[str] | None = None) -> None:
        previous = {name: var.get() for name, var in self._apk_vars.items()}
        for child in self._apk_scroll.winfo_children():
            child.destroy()
        self._apk_vars.clear()
        self._local_apks = scan_local_apks(self.apks_dir)

        if not self._local_apks:
            ctk.CTkLabel(self._apk_scroll, text="Нет APK в tv-setup/apks/", text_color=PALETTE["muted"], font=ctk.CTkFont(size=12)).pack(
                anchor="w", padx=8, pady=8
            )
            return

        for apk in self._local_apks:
            checked = True if (select_filenames and apk.filename in select_filenames) else previous.get(apk.filename, apk.package_hint is not None)
            var = tk.BooleanVar(value=checked)
            details = [part for part in (apk.package_hint, apk.version_tag) if part]
            label = f"{apk.filename} ({' · '.join(details)})" if details else apk.filename
            ctk.CTkCheckBox(self._apk_scroll, text=label, variable=var, text_color=PALETTE["text"], **_CHECKBOX_STYLE).pack(
                anchor="w", padx=6, pady=4
            )
            self._apk_vars[apk.filename] = var

    def _refresh_inspection_ui(self, inspection: DeviceInspection | None) -> None:
        self._inspection = inspection
        if inspection is None:
            self._status_label.configure(text="Статус: Не подключено", text_color=PALETTE["muted"])
        else:
            home = inspection.home_component or "не задан"
            model = inspection.model or inspection.device_id
            self._status_label.configure(
                text=f"Статус: Подключено · {model} · Android {inspection.android_version} (SDK {inspection.sdk}) · HOME: {home}",
                text_color=PALETTE["success"],
            )

        for child in self._bloat_scroll.winfo_children():
            child.destroy()
        self._bloat_vars.clear()
        self._bloat_widgets.clear()

        if inspection is None:
            self._show_bloat_placeholder("Подключите ТВ для проверки установленных пакетов")
            return
        found_entries = [entry for entry in BLOAT_CATALOG if entry.package in inspection.installed_packages]
        if not found_entries:
            self._show_bloat_placeholder("Лишних пакетов из каталога не найдено")
            return

        for entry in found_entries:
            is_disabled = entry.package in inspection.disabled_packages
            var = tk.BooleanVar(value=False if is_disabled else entry.default_checked)
            cb = ctk.CTkCheckBox(
                self._bloat_scroll,
                text=f"{entry.label} ({entry.package}) — {'Отключён' if is_disabled else 'Активен'}",
                variable=var,
                state="disabled" if is_disabled else "normal",
                text_color=PALETTE["muted"] if is_disabled else PALETTE["text"],
                **_CHECKBOX_STYLE,
            )
            cb.pack(anchor="w", padx=6, pady=3)
            self._bloat_vars[entry.package] = var
            self._bloat_widgets[entry.package] = cb

    def _show_bloat_placeholder(self, text: str) -> None:
        ctk.CTkLabel(self._bloat_scroll, text=text, text_color=PALETTE["muted"], font=ctk.CTkFont(size=12)).pack(
            anchor="w", padx=8, pady=8
        )

    def _update_devices_dropdown(self, devices: list[DiscoveredDevice]) -> None:
        previous = self._selected_device()
        previous_key = (previous.id, previous.serial) if previous else None
        self._device_by_label.clear()

        if not devices:
            self._device_menu.configure(values=[_NO_DEVICES_LABEL], state="disabled")
            self._device_var.set(_NO_DEVICES_LABEL)
            self._connect_btn.configure(text="Подключить")
            return

        labels: list[str] = []
        selected_label: str | None = None
        for device in devices:
            base = format_device_label(device)
            label, suffix = base, 2
            while label in self._device_by_label:
                label = f"{base} #{suffix}"
                suffix += 1
            self._device_by_label[label] = device
            labels.append(label)
            if previous_key == (device.id, device.serial):
                selected_label = label

        chosen = selected_label or labels[0]
        self._device_menu.configure(values=labels, state="normal")
        self._device_var.set(chosen)
        self._on_device_selected(chosen)

    def _selected_device(self) -> DiscoveredDevice | None:
        return self._device_by_label.get(self._device_var.get())

    def _on_device_selected(self, label: str) -> None:
        device = self._device_by_label.get(label)
        self._connect_btn.configure(text="Сопряжение" if device and device.status == DeviceStatus.NEEDS_PAIRING else "Подключить")

    def start_download_adb(self) -> None:
        def worker() -> None:
            self._log("Скачивание platform-tools для Windows...")
            adb_path = download_platform_tools(self.tools_dir, progress_cb=self._update_progress)
            self.adb.adb_path = adb_path
            self._log(f"ADB установлен: {adb_path}")
            self._ui_call(self._refresh_adb_status)

        self._start_worker(worker)

    def start_discovery(self) -> None:
        def worker() -> None:
            self._log("Поиск Android TV (USB, mDNS, LAN)...")
            has_adb = (self.adb.adb_path and self.adb.adb_path.is_file()) or find_adb_executable(self.tools_dir)
            devices = discover_devices(self.adb if has_adb else None)
            self._ui_call(lambda d=devices: self._update_devices_dropdown(d))
            if not devices:
                self._log("Устройства не найдены")
            else:
                for device in devices:
                    self._log(f"Найдено устройство: {format_device_label(device)}")

        self._start_worker(worker)

    def refresh_all(self) -> None:
        self._refresh_adb_status()
        self._refresh_apk_list()
        serial = self._connected_serial
        if serial is None:
            self.start_discovery()
            return

        def worker() -> None:
            self._log(f"Обновление состояния {serial}...")
            info = inspect_device(self.adb, serial)
            self._ui_call(lambda i=info: self._refresh_inspection_ui(i))
            self._log("Состояние устройства обновлено")

        self._start_worker(worker)

    def connect_selected_device(self) -> None:
        device = self._selected_device()
        if device is None:
            self._log("Сначала выберите устройство или нажмите «Найти ТВ»")
            return
        if device.status == DeviceStatus.DEBUG_OFF:
            self._log(f"{device.ip}: отладка по ADB выключена. Включите «Отладка по сети / USB» в настройках разработчика на ТВ.")
            return
        if device.status == DeviceStatus.UNAUTHORIZED:
            self._log("Подтвердите запрос отладки на экране ТВ и нажмите «Найти ТВ»")
            return
        if device.status == DeviceStatus.NEEDS_PAIRING:
            if device.pairing_port is None:
                self._open_manual_ip_dialog()
                return
            _PairingDialog(
                self, device,
                lambda code, connect_port: self._start_pair_and_connect(device.ip, device.pairing_port, code, connect_port),
            )
            return
        if device.status == DeviceStatus.CONNECTED and device.serial:
            self._start_inspect(device.serial)
        elif device.ip and device.port is not None:
            self._start_connect(f"{device.ip}:{device.port}")
        elif device.serial:
            self._start_inspect(device.serial)

    def _open_manual_ip_dialog(self) -> None:
        _ManualIpDialog(self, self._handle_manual_connect)

    def _handle_manual_connect(self, host: str, port: int, has_explicit_port: bool, pair_port: int | None, code: str) -> None:
        if code:
            actual_pair_port = pair_port if pair_port is not None else port
            connect_port = port if pair_port is not None else (None if has_explicit_port else 5555)
            self._start_pair_and_connect(host, actual_pair_port, code, connect_port)
        else:
            self._start_connect(f"{host}:{port}")

    def _start_inspect(self, serial: str) -> None:
        def worker() -> None:
            self._log(f"Инспекция устройства {serial}...")
            info = inspect_device(self.adb, serial)
            self._connected_serial = serial
            self._ui_call(self._refresh_adb_status)
            self._ui_call(lambda i=info: self._refresh_inspection_ui(i))
            self._log(f"Подключено: {info.model or info.device_id} (Android {info.android_version})")

        self._start_worker(worker)

    def _start_connect(self, target: str) -> None:
        def worker() -> None:
            self._log(f"Подключение к {target}...")
            ok, message = self.adb.connect(target)
            self._ui_call(self._refresh_adb_status)
            if not ok:
                self._log(f"Не удалось подключиться к {target}: {message}")
                return
            self._log(message or f"Подключено к {target}")
            info = inspect_device(self.adb, target)
            self._connected_serial = target
            self._ui_call(lambda i=info: self._refresh_inspection_ui(i))

        self._start_worker(worker)

    def _start_pair_and_connect(self, ip: str, pairing_port: int, code: str, connect_port: int | None) -> None:
        def worker() -> None:
            pair_target = f"{ip}:{pairing_port}"
            self._log(f"Сопряжение с {pair_target}...")
            ok, message = self.adb.pair(pair_target, code)
            self._ui_call(self._refresh_adb_status)
            if not ok:
                self._log(f"Ошибка сопряжения: {message}")
                return
            self._log(message or f"Сопряжение с {pair_target} выполнено")

            port = connect_port
            if port is None:
                self._log("Поиск порта подключения по mDNS...")
                records = browse_mdns(timeout=2.0)
                port = next(
                    (r.port for r in records if r.ip == ip and r.service_type in ("_adb-tls-connect._tcp.local.", "_adb._tcp.local.")),
                    5555,
                )
            target = f"{ip}:{port}"
            self._log(f"Подключение к {target}...")
            conn_ok, conn_msg = self.adb.connect(target)
            if not conn_ok:
                self._log(f"Сопряжение выполнено, но подключение к {target} не удалось: {conn_msg}")
                return
            info = inspect_device(self.adb, target)
            self._connected_serial = target
            self._ui_call(lambda i=info: self._refresh_inspection_ui(i))
            self._log(f"Подключено: {info.model or info.device_id}")

        self._start_worker(worker)

    def start_download_apks(self) -> None:
        def worker() -> None:
            self._log("Загрузка актуальных APK из GitHub Releases...")
            download_repo_apks(
                self.apks_dir,
                progress_cb=lambda _key, done, total: self._update_progress(done, total),
                log_cb=self._log,
            )
            self._ui_call(self._refresh_apk_list)
            self._log("Список APK обновлён")

        self._start_worker(worker)

    def add_local_apk(self) -> None:
        if self._busy:
            self._log("Дождитесь завершения текущей операции")
            return
        chosen = filedialog.askopenfilename(
            parent=self, title="Выберите APK-файл", filetypes=[("Android APK", "*.apk"), ("Все файлы", "*.*")],
        )
        if not chosen:
            return
        try:
            dest = import_local_apk(Path(chosen), self.apks_dir)
        except (OSError, ValueError) as exc:
            self._log(f"Не удалось добавить APK: {exc}")
            return
        self._refresh_apk_list(select_filenames={dest.name})
        self._log(f"Добавлен APK: {dest.name}")

    def start_apply(self) -> None:
        if self._connected_serial is None or self._inspection is None:
            self._log("Сначала подключите устройство")
            return
        serial, inspection = self._connected_serial, self._inspection
        selected_apks = [apk for apk in self._local_apks if self._apk_vars.get(apk.filename) and self._apk_vars[apk.filename].get()]
        packages_to_disable = [pkg for pkg, var in self._bloat_vars.items() if var.get() and pkg not in inspection.disabled_packages]
        plan = ApplyPlan(
            enable_fast_animations=self._fast_anim_var.get(),
            whitelist_happ=self._whitelist_happ_var.get(),
            set_home_launcher=self._set_home_var.get(),
            disable_stock_launcher=self._disable_stock_var.get(),
            packages_to_disable=packages_to_disable,
        )
        self._start_worker(lambda: self._run_apply_worker(serial, inspection, selected_apks, plan))

    def _install_selected_apks(self, serial: str, selected_apks: list[LocalApk]) -> None:
        for apk in selected_apks:
            self._log(f"Установка {apk.filename}...")
            try:
                install_apk(self.adb, serial, apk.path)
                self._log(f"Установлен {apk.filename}")
            except SignatureConflictError:
                if not apk.package_hint:
                    self._log(f"Ошибка установки {apk.filename}: конфликт подписи (имя пакета неизвестно, удалите старую версию на ТВ вручную)")
                    continue
                confirmed = self._ask_yes_no_sync(
                    "Конфликт подписи",
                    f"Пакет {apk.package_hint} уже установлен с другой подписью.\nУдалить старую версию и установить {apk.filename}?",
                )
                if not confirmed:
                    self._log(f"Пропущена установка {apk.filename}: конфликт подписи")
                    continue
                try:
                    reinstall_after_uninstall(self.adb, serial, apk.package_hint, apk.path)
                    self._log(f"Переустановлен {apk.filename}")
                except AdbError as exc:
                    self._log(f"Ошибка переустановки {apk.filename}: {exc}")
            except AdbError as exc:
                self._log(f"Ошибка установки {apk.filename}: {exc}")

    def _run_apply_worker(self, serial: str, inspection: DeviceInspection, selected_apks: list[LocalApk], plan: ApplyPlan) -> None:
        self.adb.ensure_adb()
        self._ui_call(self._refresh_adb_status)
        if selected_apks:
            self._install_selected_apks(serial, selected_apks)
            self._log("Повторная инспекция устройства после установки APK...")
            inspection = inspect_device(self.adb, serial)

        report = apply_optimizations(self.adb, serial, inspection, plan, self.state_dir, log_cb=self._log)
        self._log(f"Применение завершено: действий {len(report.actions)}, ошибок {len(report.errors)}")
        try:
            updated = inspect_device(self.adb, serial)
        except AdbError as exc:
            self._log(f"Не удалось обновить состояние после применения: {exc}")
            return
        self._ui_call(lambda info=updated: self._refresh_inspection_ui(info))

    def start_rollback(self) -> None:
        if self._connected_serial is None:
            self._log("Сначала подключите устройство")
            return
        serial = self._connected_serial

        def worker() -> None:
            self._log(f"Откат изменений на {serial}...")
            report = rollback_device(self.adb, serial, self.state_dir, log_cb=self._log)
            self._log(f"Откат завершён: действий {len(report.actions)}, ошибок {len(report.errors)}")
            try:
                updated = inspect_device(self.adb, serial)
            except AdbError as exc:
                self._log(f"Не удалось обновить состояние после отката: {exc}")
                return
            self._ui_call(lambda info=updated: self._refresh_inspection_ui(info))

        self._start_worker(worker)


def main() -> None:
    app = TvSetupApp()
    app.mainloop()
