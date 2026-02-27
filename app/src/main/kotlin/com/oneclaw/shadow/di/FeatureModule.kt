package com.oneclaw.shadow.di

import com.oneclaw.shadow.feature.provider.ProviderDetailViewModel
import com.oneclaw.shadow.feature.provider.ProviderListViewModel
import com.oneclaw.shadow.feature.provider.SetupViewModel
import com.oneclaw.shadow.feature.provider.usecase.FetchModelsUseCase
import com.oneclaw.shadow.feature.provider.usecase.SetDefaultModelUseCase
import com.oneclaw.shadow.feature.provider.usecase.TestConnectionUseCase
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.androidx.viewmodel.dsl.viewModelOf
import org.koin.dsl.module

val featureModule = module {
    // Phase 2: Provider feature use cases
    factory { TestConnectionUseCase(get()) }
    factory { FetchModelsUseCase(get()) }
    factory { SetDefaultModelUseCase(get()) }

    // Phase 2: Provider feature view models
    viewModelOf(::ProviderListViewModel)
    viewModelOf(::ProviderDetailViewModel)
    viewModelOf(::SetupViewModel)
}
