import api from './axios'
import type { ApiResponse, LoginResponse } from '@/types'

export const signup = (email: string, password: string) =>
  api.post<ApiResponse<void>>('/auth/signup', { email, password })

export const login = (email: string, password: string) =>
  api.post<ApiResponse<LoginResponse>>('/auth/login', { email, password })
