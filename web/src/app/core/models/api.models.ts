export type Role = 'STAFF' | 'PATIENT';

export interface AuthUser {
  email: string;
  name: string;
  role: Role;
  patientId: string | null;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface LoginResponse {
  token: string;
  tokenType: string;
  expiresIn: number;
  user: AuthUser;
}

export interface Provider {
  id: number;
  fullName: string;
  specialty: string;
  npiNumber: string;
  clinicLocation: string;
  active: boolean;
  createdAt: string;
}

export type SlotStatus = 'OPEN' | 'HELD' | 'BOOKED';

export interface AvailabilitySlot {
  id: number;
  providerId: number;
  providerName: string;
  specialty: string;
  clinicLocation: string;
  startTime: string;
  endTime: string;
  status: SlotStatus;
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface SearchAvailabilityParams {
  specialty?: string;
  location?: string;
  from: string;
  to: string;
  page: number;
  size: number;
}

export type AppointmentStatus = 'BOOKED' | 'CANCELLED' | 'COMPLETED';

export interface Appointment {
  id: number;
  patientId: string;
  patientName: string;
  providerId: number;
  slotId: number;
  startTime: string;
  endTime: string;
  status: AppointmentStatus;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface BookingRequest {
  patientId: string;
  patientName: string;
  providerId: number;
  slotId: number;
  startTime: string;
  endTime: string;
}

export interface ApiError {
  error: string;
  message: string;
  timestamp?: string;
  path?: string;
  fields?: Record<string, string> | null;
}

export const SPECIALTIES: readonly string[] = [
  'Cardiology',
  'Dermatology',
  'Endocrinology',
  'Gastroenterology',
  'Neurology',
  'Orthopedics',
  'Pediatrics',
  'Psychiatry',
];

export const LOCATIONS: readonly string[] = [
  'Downtown Clinic',
  'Eastgate Health',
  'Lakeview Pavilion',
  'Northside Medical Center',
  'Westpark Clinic',
];
