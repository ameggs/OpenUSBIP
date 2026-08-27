package io.openusbip.server.mtp

/**
 * MTP / PTP codes.
 *
 * MTP is PTP (ISO 15740) plus Microsoft's extensions, so the operation space is
 * split: 0x1xxx is standard PTP, 0x9xxx is MTP-specific. Response codes split
 * the same way, 0x2xxx and 0xAxxx.
 *
 * Unlike USB/IP's big-endian headers, everything in a PTP container is
 * LITTLE-endian -- the same trap as the mass-storage path.
 */
object MtpConstants {

    // --- container types --------------------------------------------------

    const val CONTAINER_COMMAND = 1
    const val CONTAINER_DATA = 2
    const val CONTAINER_RESPONSE = 3
    const val CONTAINER_EVENT = 4

    /** length(4) + type(2) + code(2) + transactionId(4) */
    const val CONTAINER_HEADER_SIZE = 12

    // --- operations we implement -----------------------------------------

    const val OP_GET_DEVICE_INFO = 0x1001
    const val OP_OPEN_SESSION = 0x1002
    const val OP_CLOSE_SESSION = 0x1003
    const val OP_GET_STORAGE_IDS = 0x1004
    const val OP_GET_STORAGE_INFO = 0x1005
    const val OP_GET_NUM_OBJECTS = 0x1006
    const val OP_GET_OBJECT_HANDLES = 0x1007
    const val OP_GET_OBJECT_INFO = 0x1008
    const val OP_GET_OBJECT = 0x1009
    const val OP_DELETE_OBJECT = 0x100B
    const val OP_SEND_OBJECT_INFO = 0x100C
    const val OP_SEND_OBJECT = 0x100D
    const val OP_GET_DEVICE_PROP_DESC = 0x1014
    const val OP_GET_DEVICE_PROP_VALUE = 0x1015
    const val OP_GET_PARTIAL_OBJECT = 0x101B

    /** MTP extensions. GetObjectPropList is what makes directory listing fast. */
    const val OP_GET_OBJECT_PROPS_SUPPORTED = 0x9801
    const val OP_GET_OBJECT_PROP_DESC = 0x9802
    const val OP_GET_OBJECT_PROP_VALUE = 0x9803
    const val OP_SET_OBJECT_PROP_VALUE = 0x9804
    const val OP_GET_OBJECT_PROP_LIST = 0x9805
    const val OP_GET_OBJECT_REFERENCES = 0x9810

    // --- response codes ---------------------------------------------------

    const val RESP_OK = 0x2001
    const val RESP_GENERAL_ERROR = 0x2002
    const val RESP_SESSION_NOT_OPEN = 0x2003
    const val RESP_INVALID_TRANSACTION_ID = 0x2004
    const val RESP_OPERATION_NOT_SUPPORTED = 0x2005
    const val RESP_PARAMETER_NOT_SUPPORTED = 0x2006
    const val RESP_INCOMPLETE_TRANSFER = 0x2007
    const val RESP_INVALID_STORAGE_ID = 0x2008
    const val RESP_INVALID_OBJECT_HANDLE = 0x2009
    const val RESP_DEVICE_PROP_NOT_SUPPORTED = 0x200A
    const val RESP_STORE_FULL = 0x200C
    const val RESP_ACCESS_DENIED = 0x200F
    const val RESP_STORE_NOT_AVAILABLE = 0x2013
    const val RESP_SPECIFICATION_BY_FORMAT_UNSUPPORTED = 0x2014
    const val RESP_NO_VALID_OBJECT_INFO = 0x2015
    const val RESP_SESSION_ALREADY_OPEN = 0x201E
    const val RESP_TRANSACTION_CANCELLED = 0x201F
    const val RESP_INVALID_PARENT_OBJECT = 0xA003
    const val RESP_INVALID_OBJECT_PROP_CODE = 0xA801
    const val RESP_OBJECT_PROP_NOT_SUPPORTED = 0xA80A

    // --- object formats ---------------------------------------------------

    const val FORMAT_UNDEFINED = 0x3000
    const val FORMAT_ASSOCIATION = 0x3001          // a directory
    const val FORMAT_TEXT = 0x3004
    const val FORMAT_HTML = 0x3005
    const val FORMAT_MP3 = 0x3009
    const val FORMAT_EXIF_JPEG = 0x3801
    const val FORMAT_BMP = 0x3804
    const val FORMAT_GIF = 0x3807
    const val FORMAT_PNG = 0x380B
    const val FORMAT_MP4 = 0xB982
    const val FORMAT_FLAC = 0xB906
    const val FORMAT_WAV = 0x3008
    const val FORMAT_PDF = 0xBA03

    /** Association type for a plain folder. */
    const val ASSOCIATION_GENERIC_FOLDER = 0x0001

    // --- object properties ------------------------------------------------

    const val PROP_STORAGE_ID = 0xDC01
    const val PROP_OBJECT_FORMAT = 0xDC02
    const val PROP_PROTECTION_STATUS = 0xDC03
    const val PROP_OBJECT_SIZE = 0xDC04
    const val PROP_OBJECT_FILE_NAME = 0xDC07
    const val PROP_DATE_CREATED = 0xDC08
    const val PROP_DATE_MODIFIED = 0xDC09
    const val PROP_PARENT_OBJECT = 0xDC0B
    const val PROP_PERSISTENT_UID = 0xDC41
    const val PROP_NAME = 0xDC44
    const val PROP_DISPLAY_NAME = 0xDCE0

    // --- device properties ------------------------------------------------

    const val DEVICE_PROP_BATTERY_LEVEL = 0x5001
    const val DEVICE_PROP_DEVICE_FRIENDLY_NAME = 0xD402
    const val DEVICE_PROP_SYNCHRONIZATION_PARTNER = 0xD401

    // --- data type codes, for property descriptors ------------------------

    const val TYPE_UNDEFINED = 0x0000
    const val TYPE_INT8 = 0x0001
    const val TYPE_UINT8 = 0x0002
    const val TYPE_INT16 = 0x0003
    const val TYPE_UINT16 = 0x0004
    const val TYPE_INT32 = 0x0005
    const val TYPE_UINT32 = 0x0006
    const val TYPE_INT64 = 0x0007
    const val TYPE_UINT64 = 0x0008
    const val TYPE_UINT128 = 0x000A
    const val TYPE_AUINT8 = 0x4002
    const val TYPE_AUINT16 = 0x4004
    const val TYPE_AUINT32 = 0x4006
    const val TYPE_STR = 0xFFFF

    // --- storage ----------------------------------------------------------

    /**
     * One storage, whose id is (physical << 16) | logical. Android uses
     * 0x00010001 for internal storage and clients expect a nonzero value.
     */
    const val STORAGE_ID = 0x00010001

    const val STORAGE_TYPE_FIXED_RAM = 0x0003
    const val FILESYSTEM_TYPE_HIERARCHICAL = 0x0002
    const val ACCESS_READ_WRITE = 0x0000
    const val ACCESS_READ_ONLY_NO_DELETE = 0x0001

    // --- handles ----------------------------------------------------------

    /**
     * In GetObjectHandles, a parent of 0xFFFFFFFF means "objects in the root",
     * while 0 means "all objects on the storage, at any depth". Confusing these
     * makes a client either see nothing or recurse the whole tree.
     */
    const val HANDLE_ROOT = 0xFFFFFFFF.toInt()
    const val HANDLE_ALL = 0x00000000

    const val PROTECTION_NONE = 0x0000
    const val PROTECTION_READ_ONLY = 0x0001
}
