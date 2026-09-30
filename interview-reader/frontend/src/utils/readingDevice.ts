const DEVICE_ID_KEY = "reader.deviceId";

export interface DeviceIdStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

export function getOrCreateReadingDeviceId(
  storage: DeviceIdStorage = localStorage,
  createId: () => string = () => crypto.randomUUID()
): string {
  const existing = storage.getItem(DEVICE_ID_KEY);
  if (existing) return existing;
  const deviceId = createId();
  storage.setItem(DEVICE_ID_KEY, deviceId);
  return deviceId;
}
