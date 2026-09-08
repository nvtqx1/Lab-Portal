import { zodResolver } from '@hookform/resolvers/zod';
import axios from 'axios';
import { useEffect, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';

import { Button, toast } from '../../../shared/components';
import type { Response } from '../../../shared/types';
import { VALIDATION_MESSAGES } from '../../../shared/utils';
import type { UserMembershipResponse } from '../api';
import { useCurrentUser, useUpdateProfile } from '../hooks';

const profileSchema = z.object({
  fullName: z.string().trim().min(1, VALIDATION_MESSAGES.required).max(100, 'Họ tên tối đa 100 ký tự'),
  phone: z
    .string()
    .trim()
    .regex(/^[+]?[0-9]{10,15}$/, 'Số điện thoại phải có 10-15 chữ số, có thể bắt đầu bằng +')
    .or(z.literal('')),
  avatarUrl: z
    .string()
    .trim()
    .url('Avatar URL không hợp lệ')
    .or(z.literal('')),
});

type ProfileFormValues = z.infer<typeof profileSchema>;

function getApiErrorMessage(error: unknown, fallback: string) {
  if (axios.isAxiosError(error)) {
    const response = error.response?.data as Partial<Response<unknown>> | undefined;
    return response?.message ?? fallback;
  }

  if (error instanceof Error) {
    return error.message;
  }

  return fallback;
}

function getLabName(membership: UserMembershipResponse) {
  return membership.labName ?? membership.lab?.name ?? membership.lab?.labName ?? `PTN #${membership.labId ?? membership.lab?.id ?? membership.id ?? ''}`;
}

function formatRole(role: string) {
  const normalizedRole = role.replace(/^ROLE_/, '');
  return normalizedRole === 'LAB_MANAGER' ? 'Quản lý PTN' : normalizedRole === 'STUDENT' ? 'Sinh viên' : normalizedRole;
}

export function ProfilePage() {
  const { data: profile, isLoading, isError, error } = useCurrentUser();
  const updateProfileMutation = useUpdateProfile();
  const [isEditing, setIsEditing] = useState(false);
  const [formMessage, setFormMessage] = useState<string | null>(null);

  const canEditAvatarUrl = profile ? Object.prototype.hasOwnProperty.call(profile, 'avatarUrl') : false;
  const displayName = profile?.fullName || profile?.username || profile?.email || 'Người dùng';
  const activeMemberships = useMemo(
    () => profile?.memberships?.filter((membership) => membership.status?.toUpperCase() === 'ACTIVE') ?? [],
    [profile?.memberships],
  );
  const managedLab = profile?.managedLab;
  const managedLabName = managedLab?.name ?? managedLab?.labName ?? null;
  const managedLabId = managedLab?.id ?? profile?.managedLabId ?? null;
  const managedLabEntries = useMemo(
    () => [
      ...(managedLabName ? [{ key: `managed-${managedLabId ?? managedLabName}`, name: managedLabName, status: 'Đang quản lý' }] : []),
      ...activeMemberships
        .filter((membership) => {
          const membershipId = membership.labId ?? membership.lab?.id ?? membership.id;
          return !(managedLabId && membershipId === managedLabId) && getLabName(membership) !== managedLabName;
        })
        .map((membership, index) => ({
          key: `${membership.labId ?? membership.lab?.id ?? membership.id ?? index}-${membership.status}`,
          name: getLabName(membership),
          status: 'Đang hoạt động',
        })),
    ],
    [activeMemberships, managedLabId, managedLabName],
  );

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isDirty },
  } = useForm<ProfileFormValues>({
    resolver: zodResolver(profileSchema),
    defaultValues: {
      fullName: '',
      phone: '',
      avatarUrl: '',
    },
  });

  useEffect(() => {
    if (!profile) {
      return;
    }

    reset({
      fullName: profile.fullName ?? '',
      phone: profile.phone ?? '',
      avatarUrl: profile.avatarUrl ?? '',
    });
  }, [profile, reset]);

  const onSubmit = async (values: ProfileFormValues) => {
    setFormMessage(null);

    try {
      await updateProfileMutation.mutateAsync({
        fullName: values.fullName.trim(),
        phone: values.phone.trim() || null,
        ...(canEditAvatarUrl ? { avatarUrl: values.avatarUrl.trim() || null } : {}),
      });
      setIsEditing(false);
      setFormMessage('Cập nhật hồ sơ thành công.');
      toast.success('Cập nhật hồ sơ thành công.');
    } catch (mutationError) {
      const message = getApiErrorMessage(mutationError, 'Không thể cập nhật hồ sơ.');
      setFormMessage(message);
      toast.error(message);
    }
  };

  if (isLoading) {
    return (
      <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <div className="h-6 w-40 animate-pulse rounded bg-slate-200" />
        <div className="mt-6 grid gap-4 sm:grid-cols-2">
          <div className="h-16 animate-pulse rounded bg-slate-100" />
          <div className="h-16 animate-pulse rounded bg-slate-100" />
          <div className="h-16 animate-pulse rounded bg-slate-100" />
          <div className="h-16 animate-pulse rounded bg-slate-100" />
        </div>
      </section>
    );
  }

  if (isError || !profile) {
    return (
      <section className="rounded-lg border border-red-200 bg-white p-6 text-sm text-red-700 shadow-sm">
        Không thể tải hồ sơ. {getApiErrorMessage(error, '')}
      </section>
    );
  }

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
      <div className="flex flex-col gap-4 border-b border-slate-200 pb-6 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-4">
          {profile.avatarUrl ? (
            <img
              alt={displayName}
              className="h-16 w-16 rounded-full border border-slate-200 object-cover"
              src={profile.avatarUrl}
            />
          ) : (
            <div className="flex h-16 w-16 items-center justify-center rounded-full bg-slate-900 text-xl font-semibold text-white">
              {displayName.charAt(0).toUpperCase()}
            </div>
          )}
          <div>
          <h1 className="text-xl font-semibold text-slate-950">{displayName}</h1>
            <p className="mt-1 text-sm text-slate-600">{profile.email}</p>
            <p className="mt-1 text-xs font-medium uppercase text-slate-500">{profile.roles.map(formatRole).join(', ')}</p>
          </div>
        </div>

        {!isEditing ? (
          <Button
            onClick={() => {
              setFormMessage(null);
              setIsEditing(true);
            }}
          >
            Chỉnh sửa
          </Button>
        ) : null}
      </div>

      {formMessage ? (
        <div
          className={[
            'mt-5 rounded-md border px-3 py-2 text-sm',
            updateProfileMutation.isError
              ? 'border-red-200 bg-red-50 text-red-700'
              : 'border-emerald-200 bg-emerald-50 text-emerald-700',
          ].join(' ')}
        >
          {formMessage}
        </div>
      ) : null}

      {!isEditing ? (
        <div className="mt-6 space-y-8">
          <dl className="grid gap-5 sm:grid-cols-2">
            <div>
              <dt className="text-sm font-medium text-slate-500">Họ tên</dt>
              <dd className="mt-1 text-sm text-slate-950">{profile.fullName}</dd>
            </div>
            <div>
              <dt className="text-sm font-medium text-slate-500">Email</dt>
              <dd className="mt-1 text-sm text-slate-950">{profile.email}</dd>
            </div>
            <div>
              <dt className="text-sm font-medium text-slate-500">Số điện thoại</dt>
              <dd className="mt-1 text-sm text-slate-950">{profile.phone || 'Chưa cập nhật'}</dd>
            </div>
            <div>
              <dt className="text-sm font-medium text-slate-500">Vai trò</dt>
              <dd className="mt-1 text-sm text-slate-950">{profile.roles.map(formatRole).join(', ')}</dd>
            </div>
          </dl>

          <div>
            <h3 className="text-sm font-semibold text-slate-950">Thông tin PTN</h3>
            {managedLabEntries.length ? (
              <ul className="mt-3 divide-y divide-slate-200 rounded-md border border-slate-200">
                {managedLabEntries.map((lab) => (
                  <li
                    className="flex items-center justify-between gap-3 px-4 py-3 text-sm"
                    key={lab.key}
                  >
                    <span className="font-medium text-slate-950">{lab.name}</span>
                    <span className="rounded-full bg-emerald-50 px-2 py-1 text-xs font-medium text-emerald-700">
                      {lab.status}
                    </span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="mt-2 text-sm text-slate-600">Chưa tham gia PTN nào</p>
            )}
          </div>
        </div>
      ) : (
        <form className="mt-6 max-w-xl space-y-5" onSubmit={handleSubmit(onSubmit)}>
          <div>
            <label className="block text-sm font-medium text-slate-700" htmlFor="fullName">
              Họ tên
            </label>
            <input
              id="fullName"
              className="mt-2 block w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-950 shadow-sm outline-none transition focus:border-slate-900 focus:ring-2 focus:ring-slate-900/10"
              disabled={updateProfileMutation.isPending}
              {...register('fullName')}
            />
            {errors.fullName ? <p className="mt-2 text-sm text-red-600">{errors.fullName.message}</p> : null}
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-700" htmlFor="phone">
              Số điện thoại
            </label>
            <input
              id="phone"
              className="mt-2 block w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-950 shadow-sm outline-none transition focus:border-slate-900 focus:ring-2 focus:ring-slate-900/10"
              disabled={updateProfileMutation.isPending}
              placeholder="+84901234567"
              {...register('phone')}
            />
            {errors.phone ? <p className="mt-2 text-sm text-red-600">{errors.phone.message}</p> : null}
          </div>

          {canEditAvatarUrl ? (
            <div>
              <label className="block text-sm font-medium text-slate-700" htmlFor="avatarUrl">
                Avatar URL
              </label>
              <input
                id="avatarUrl"
                className="mt-2 block w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-950 shadow-sm outline-none transition focus:border-slate-900 focus:ring-2 focus:ring-slate-900/10"
                disabled={updateProfileMutation.isPending}
                placeholder="https://example.com/avatar.jpg"
                {...register('avatarUrl')}
              />
              {errors.avatarUrl ? <p className="mt-2 text-sm text-red-600">{errors.avatarUrl.message}</p> : null}
            </div>
          ) : null}

          <div className="flex gap-3">
            <Button
              disabled={updateProfileMutation.isPending || !isDirty}
              loading={updateProfileMutation.isPending}
              loadingText="Đang lưu..."
              type="submit"
            >
              Lưu
            </Button>
            <Button
              disabled={updateProfileMutation.isPending}
              variant="outline"
              onClick={() => {
                reset({
                  fullName: profile.fullName ?? '',
                  phone: profile.phone ?? '',
                  avatarUrl: profile.avatarUrl ?? '',
                });
                setFormMessage(null);
                setIsEditing(false);
              }}
            >
              Hủy
            </Button>
          </div>
        </form>
      )}
    </section>
  );
}
