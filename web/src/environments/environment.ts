export interface Environment {
  production: boolean;
  /**
   * Empty string means same-origin. In development proxy.conf.json forwards /api/providers and
   * /api/availability to provider-service; in Docker nginx does the same.
   */
  providerApiBaseUrl: string;
  /** Empty string means same-origin; proxied/nginx-routed to appointment-service. */
  appointmentApiBaseUrl: string;
}

export const environment: Environment = {
  production: false,
  providerApiBaseUrl: '',
  appointmentApiBaseUrl: '',
};
