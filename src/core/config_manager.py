"""
Configuration Manager
Handles saving and loading application settings
"""

import os
import yaml
from pathlib import Path
from typing import Dict, List, Any, Optional
from dataclasses import dataclass, asdict

import sys

# Ensure the repository root is importable so `src.*` resolves consistently,
# whether this module is imported by an entry point or run directly.
_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from src.core.server_discovery import USBIPServer
from src.utils.logger import setup_logger

logger = setup_logger(__name__)


@dataclass
class AppConfig:
    """Application configuration"""
    # Discovery settings
    auto_discovery_enabled: bool = True
    discovery_interval: int = 5  # seconds
    
    # UI settings
    window_width: int = 900
    window_height: int = 600
    window_x: Optional[int] = None
    window_y: Optional[int] = None
    
    # Behavior settings
    auto_attach_on_startup: bool = False
    minimize_to_tray: bool = False
    show_notifications: bool = True
    
    # Manual servers
    manual_servers: Optional[List[Dict[str, Any]]] = None
    
    # Auto-attach devices (busid -> server_ip mapping)
    auto_attach_devices: Optional[Dict[str, str]] = None
    
    def __post_init__(self):
        if self.manual_servers is None:
            self.manual_servers = []
        if self.auto_attach_devices is None:
            self.auto_attach_devices = {}


class ConfigManager:
    """Manages application configuration"""
    
    def __init__(self, config_dir: Optional[Path] = None):
        """
        Initialize config manager
        
        Args:
            config_dir: Directory to store config file
                       If None, uses ~/.config/openusbip (Linux) or %APPDATA%/OpenUSBIP (Windows)
        """
        if config_dir is None:
            config_dir = self.get_default_config_dir()
        
        self.config_dir = Path(config_dir)
        self.config_file = self.config_dir / "config.yaml"
        self.config = AppConfig()
        
        # Create config directory if it doesn't exist
        self.config_dir.mkdir(parents=True, exist_ok=True)
        
        # Load existing config
        self.load()
    
    @staticmethod
    def get_default_config_dir() -> Path:
        """Get default configuration directory for the platform"""
        if os.name == 'nt':  # Windows
            appdata = os.getenv('APPDATA', os.path.expanduser('~'))
            return Path(appdata) / 'OpenUSBIP'
        else:  # Linux/Unix
            config_home = os.getenv('XDG_CONFIG_HOME', os.path.expanduser('~/.config'))
            return Path(config_home) / 'openusbip'
    
    def load(self) -> bool:
        """
        Load configuration from file
        
        Returns:
            True if loaded successfully, False otherwise
        """
        if not self.config_file.exists():
            logger.info(f"Config file not found: {self.config_file}")
            return False
        
        try:
            with open(self.config_file, 'r') as f:
                data = yaml.safe_load(f)
            
            if data:
                # Update config with loaded data
                for key, value in data.items():
                    if hasattr(self.config, key):
                        setattr(self.config, key, value)
                
                logger.info(f"Configuration loaded from {self.config_file}")
                return True
        
        except Exception as e:
            logger.error(f"Failed to load config: {e}")
            return False
        
        return False
    
    def save(self) -> bool:
        """
        Save configuration to file
        
        Returns:
            True if saved successfully, False otherwise
        """
        try:
            # Convert config to dict
            data = asdict(self.config)
            
            # Write to file
            with open(self.config_file, 'w') as f:
                yaml.dump(data, f, default_flow_style=False, sort_keys=False)
            
            logger.info(f"Configuration saved to {self.config_file}")
            return True
        
        except Exception as e:
            logger.error(f"Failed to save config: {e}")
            return False
    
    def add_manual_server(self, server: USBIPServer):
        """Add a manual server to config"""
        if self.config.manual_servers is None:
            self.config.manual_servers = []
        
        server_dict = server.to_dict()
        
        # Check if already exists
        for existing in self.config.manual_servers:
            if existing.get('ip_address') == server.ip_address:
                # Update existing
                existing.update(server_dict)
                logger.info(f"Updated server in config: {server.name}")
                return
        
        # Add new
        self.config.manual_servers.append(server_dict)
        logger.info(f"Added server to config: {server.name}")
    
    def remove_manual_server(self, ip_address: str):
        """Remove a manual server from config"""
        if self.config.manual_servers is None:
            return
        
        self.config.manual_servers = [
            s for s in self.config.manual_servers
            if s.get('ip_address') != ip_address
        ]
        logger.info(f"Removed server from config: {ip_address}")
    
    def get_manual_servers(self) -> List[USBIPServer]:
        """Get list of manual servers"""
        if self.config.manual_servers is None:
            return []
        
        servers = []
        for server_dict in self.config.manual_servers:
            try:
                server = USBIPServer.from_dict(server_dict)
                servers.append(server)
            except Exception as e:
                logger.error(f"Failed to load server from config: {e}")
        return servers
    
    def add_auto_attach_device(self, busid: str, server_ip: str):
        """Add a device to auto-attach list"""
        if self.config.auto_attach_devices is None:
            self.config.auto_attach_devices = {}
        
        self.config.auto_attach_devices[busid] = server_ip
        logger.info(f"Added auto-attach device: {busid} from {server_ip}")
    
    def remove_auto_attach_device(self, busid: str):
        """Remove a device from auto-attach list"""
        if self.config.auto_attach_devices is None:
            return
        
        if busid in self.config.auto_attach_devices:
            del self.config.auto_attach_devices[busid]
            logger.info(f"Removed auto-attach device: {busid}")
    
    def get_auto_attach_devices(self) -> Dict[str, str]:
        """Get auto-attach devices mapping"""
        if self.config.auto_attach_devices is None:
            return {}
        return self.config.auto_attach_devices.copy()
    
    def set_window_geometry(self, x: int, y: int, width: int, height: int):
        """Save window geometry"""
        self.config.window_x = x
        self.config.window_y = y
        self.config.window_width = width
        self.config.window_height = height
    
    def get_window_geometry(self) -> tuple:
        """Get window geometry (x, y, width, height)"""
        return (
            self.config.window_x,
            self.config.window_y,
            self.config.window_width,
            self.config.window_height
        )


if __name__ == "__main__":
    # Test config manager
    print("Testing ConfigManager...")
    
    config_mgr = ConfigManager()
    print(f"Config directory: {config_mgr.config_dir}")
    print(f"Config file: {config_mgr.config_file}")
    
    # Add a test server
    from src.core.server_discovery import USBIPServer
    test_server = USBIPServer(
        name="Test Pi",
        hostname="testpi.local",
        ip_address="192.168.1.100",
        port=3240,
        server_type="raspberry-pi"
    )
    
    config_mgr.add_manual_server(test_server)
    config_mgr.add_auto_attach_device("1-1", "192.168.1.100")
    
    # Save
    if config_mgr.save():
        print("✅ Config saved successfully")
    
    # Load
    config_mgr2 = ConfigManager()
    servers = config_mgr2.get_manual_servers()
    print(f"Loaded {len(servers)} manual servers:")
    for server in servers:
        print(f"  - {server.display_name}")
    
    auto_attach = config_mgr2.get_auto_attach_devices()
    print(f"Auto-attach devices: {auto_attach}")
