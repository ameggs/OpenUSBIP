"""
Logging configuration for OpenUSBIP
"""

import logging
import sys
from pathlib import Path

try:
    import colorlog  # type: ignore
    HAS_COLORLOG = True
except ImportError:
    HAS_COLORLOG = False
    colorlog = None  # type: ignore


def setup_logger(
    name: str = "OpenUSBIP",
    level: int = logging.INFO,
    log_file: Path | None = None
) -> logging.Logger:
    """
    Set up application logger with color support

    Args:
        name: Logger name
        level: Logging level
        log_file: Optional file path for logging

    Returns:
        Configured logger instance
    """
    logger = logging.getLogger(name)
    logger.setLevel(level)

    # Remove existing handlers
    logger.handlers.clear()

    # Console handler with colors
    console_handler = logging.StreamHandler(sys.stdout)
    console_handler.setLevel(level)

    if HAS_COLORLOG and colorlog:
        formatter = colorlog.ColoredFormatter(  # type: ignore
            "%(log_color)s%(levelname)-8s%(reset)s %(blue)s%(name)s%(reset)s: %(message)s",
            datefmt=None,
            reset=True,
            log_colors={
                'DEBUG': 'cyan',
                'INFO': 'green',
                'WARNING': 'yellow',
                'ERROR': 'red',
                'CRITICAL': 'red,bg_white',
            }
        )
    else:
        formatter = logging.Formatter(
            "%(levelname)-8s %(name)s: %(message)s"
        )

    console_handler.setFormatter(formatter)
    logger.addHandler(console_handler)

    # File handler (if specified)
    if log_file:
        log_file.parent.mkdir(parents=True, exist_ok=True)
        file_handler = logging.FileHandler(log_file)
        file_handler.setLevel(logging.DEBUG)  # Always debug in file
        file_formatter = logging.Formatter(
            "%(asctime)s - %(name)s - %(levelname)s - %(message)s"
        )
        file_handler.setFormatter(file_formatter)
        logger.addHandler(file_handler)

    return logger


# Create default logger
logger = setup_logger()


if __name__ == "__main__":
    # Test logging
    test_logger = setup_logger("Test", logging.DEBUG)
    test_logger.debug("Debug message")
    test_logger.info("Info message")
    test_logger.warning("Warning message")
    test_logger.error("Error message")
    test_logger.critical("Critical message")
