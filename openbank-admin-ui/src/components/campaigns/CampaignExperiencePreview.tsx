// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { EditorMobileDestination, EditorStep } from '@/components/campaigns/JourneyEditor'

type Destination = Exclude<EditorMobileDestination, undefined>

const destinationCopy: Record<Destination, { cs: string; en: string; eyebrowCs: string; eyebrowEn: string }> = {
  HOME: { cs: 'Domovská obrazovka', en: 'Home', eyebrowCs: 'Dnešní přehled', eyebrowEn: 'Today at a glance' },
  SAVINGS: { cs: 'Spoření', en: 'Savings', eyebrowCs: 'Vaše spoření', eyebrowEn: 'Your savings' },
  CARDS: { cs: 'Karty', en: 'Cards', eyebrowCs: 'Vaše karty', eyebrowEn: 'Your cards' },
  PAYMENTS: { cs: 'Platby', en: 'Payments', eyebrowCs: 'Poslat peníze', eyebrowEn: 'Move money' },
  PRODUCT_HUB: { cs: 'Produkty', en: 'Products', eyebrowCs: 'Pro vás', eyebrowEn: 'Picked for you' },
}

/**
 * A deliberately faithful *structure* preview, not an invented content renderer.
 *
 * Campaign-service authorises a closed application destination, not arbitrary native UI or copy.
 * The preview therefore demonstrates the customer sequence (notification → authenticated app →
 * destination) and uses the selected template headline, while never pretending that Admin UI owns
 * the mobile app. It keeps the operator's decision tangible without becoming a second app client.
 */
export function CampaignExperiencePreview({
  step,
  campaignName,
}: {
  step: EditorStep | undefined
  campaignName: string
}) {
  const { t, language } = useLanguage()
  const isPush = step?.channel === 'PUSH'
  const isBanner = step?.channel === 'BANNER'
  const destination = step?.mobileDestination ?? 'HOME'
  const copy = destinationCopy[destination]
  const headline = step?.variables.offerTitle?.trim() || t('Vaše další chytrá volba', 'Your next smart move')
  const campaignLabel = campaignName.trim() || t('Nová kampaň', 'New campaign')

  return (
    <section className="campaign-experience-preview" aria-labelledby="experience-preview-title" data-testid="campaign-experience-preview">
      <div className="campaign-preview-heading">
        <div>
          <p className="campaign-preview-kicker">{t('Zážitek zákazníka', 'Customer experience')}</p>
          <h3 id="experience-preview-title">{t('Na telefonu, ne v tabulce', 'On the phone, not in a table')}</h3>
        </div>
        <span className="campaign-preview-live">{t('Živý náhled', 'Live preview')}</span>
      </div>

      <div className="campaign-preview-stage">
        <div className="campaign-phone" aria-label={t('Náhled mobilní aplikace', 'Mobile app preview')}>
          <div className="campaign-phone-island" />
          <div className="campaign-phone-content">
            <div className="campaign-phone-topline">
              <span>openbank</span>
              <span>•••</span>
            </div>
            <p className="campaign-phone-eyebrow">{language === 'cs' ? copy.eyebrowCs : copy.eyebrowEn}</p>
            <h4>{language === 'cs' ? copy.cs : copy.en}</h4>
            <div className={`campaign-phone-hero${isBanner ? ' campaign-phone-banner' : ''}`}>
              <span>{isBanner ? t('Pro vás v aplikaci', 'For you in the app') : t('Doporučeno pro vás', 'Recommended for you')}</span>
              <strong>{headline}</strong>
              <button type="button" tabIndex={-1}>{t('Zjistit víc', 'Explore')}</button>
            </div>
            <div className="campaign-phone-row"><span /><span /><span /></div>
          </div>
        </div>

        <div className={`campaign-push-card${isBanner ? ' campaign-banner-card' : ''}`} data-preview-channel={isPush ? 'PUSH' : isBanner ? 'BANNER' : 'EMAIL'}>
          <div className="campaign-push-icon">o</div>
          <div>
            <div className="campaign-push-meta">OPENBANK · {isBanner ? t('DOMOVSKÁ OBRAZOVKA', 'HOME SCREEN') : t('nyní', 'now')}</div>
            <p className="campaign-push-title">{isPush ? headline : isBanner ? t('Banner v přihlášené aplikaci', 'Banner in the signed-in app') : t('Zvolte push krok', 'Choose a push step')}</p>
            <p>{isPush
              ? t('Otevře zabezpečenou aplikaci — bez osobního obsahu v notifikaci.', 'Opens the secure app — with no personal content in the notification.')
              : isBanner
                ? t('Zobrazí se při další návštěvě domova. Žádné vyrušení, žádná zamčená obrazovka.', 'Shown on the next home visit. No interruption, no lock screen.')
              : t('Tento krok je e-mail. Vyberte push krok na cestě pro náhled notifikace.', 'This step is email. Select a push step on the journey to preview the notification.')}</p>
          </div>
        </div>
      </div>

      <div className="campaign-preview-route">
        <span>{isBanner ? t('Po klepnutí na banner', 'After banner tap') : t('Po klepnutí', 'After tap')}</span>
        <strong>{campaignLabel} → {language === 'cs' ? copy.cs : copy.en}</strong>
        <code>{`openbank://${destination.toLowerCase().replace('product_hub', 'products')}`}</code>
      </div>
    </section>
  )
}
