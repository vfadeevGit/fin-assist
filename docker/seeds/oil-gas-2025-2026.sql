-- Local development demo data only. Company names are illustrative;
-- projects, contracts, dates and amounts are fictional. Amounts represent RUB.
-- INN/KPP are deliberately NULL; these are not verified legal counterparties.
-- Run from repository root:
-- docker compose -f docker/docker-compose.dev.yml exec -T postgres \
--   psql -U postgres -d finassist -v ON_ERROR_STOP=1 < docker/seeds/oil-gas-2025-2026.sql
-- Stable IDs make reruns insert-only: existing records are never overwritten.
-- Source for customer selection (not a ranking):
-- https://www.eia.gov/international/content/analysis/countries_long/russia/
-- https://www.mordorintelligence.com/industry-reports/russian-federation-oil-and-gas-market/companies

BEGIN;
SET LOCAL lock_timeout = '5s';

DO $$
DECLARE
    tag constant text := 'demo-oilgas-2025-2026';
    companies text[] := ARRAY['Роснефть', 'Газпром', 'ЛУКОЙЛ', 'Газпром нефть',
        'НОВАТЭК', 'Сургутнефтегаз', 'Татнефть', 'Транснефть'];
    regions text[] := ARRAY['Красноярский край', 'Ямало-Ненецкий АО', 'Республика Коми',
        'Омская область', 'Ямало-Ненецкий АО', 'Ханты-Мансийский АО',
        'Республика Татарстан', 'Самарская область'];
    sectors text[] := ARRAY['Насосное и компрессорное оборудование',
        'Автоматизация и КИПиА', 'Трубопроводная арматура', 'Электроснабжение объектов'];
    works text[] := ARRAY['Поставка насосных и компрессорных агрегатов',
        'Модернизация систем автоматизации и КИПиА',
        'Поставка запорной и регулирующей арматуры',
        'Обновление систем электроснабжения'];
    weights numeric[] := ARRAY[0.10, 0.15, 0.20, 0.20, 0.20, 0.15];
    company_no integer;
    period_no integer;
    project_no integer;
    item_no integer;
    sector_no integer;
    customer_id uuid;
    contract_id uuid;
    project_id uuid;
    sector_id uuid;
    payment_id uuid;
    shipment_id uuid;
    key text;
    period_label text;
    start_date date;
    end_date date;
    first_shipment date;
    shipment_date date;
    total numeric(19,2);
    project_total numeric(19,2);
    installment numeric(19,2);
BEGIN
    IF current_database() <> 'finassist' THEN
        RAISE EXCEPTION 'This seed is intended for the local finassist database';
    END IF;

    FOR sector_no IN 1..4 LOOP
        INSERT INTO line_of_business (id, version, created_by, created_date, name)
        VALUES (md5(tag || ':sector:' || sector_no)::uuid, 1, tag, now(),
            sectors[sector_no] || ' (демо)') ON CONFLICT (id) DO NOTHING;
    END LOOP;

    FOR company_no IN 1..8 LOOP
        customer_id := md5(tag || ':customer:' || company_no)::uuid;
        INSERT INTO customer (id, version, created_by, created_date, name)
        VALUES (customer_id, 1, tag, now(), companies[company_no] || ' (демо)')
        ON CONFLICT (id) DO NOTHING;

        FOR period_no IN 1..3 LOOP
            period_label := (ARRAY['2025', '2026', '2025-2026'])[period_no];
            start_date := (ARRAY[DATE '2025-01-10', DATE '2026-01-10', DATE '2025-07-01'])[period_no];
            end_date := (ARRAY[DATE '2025-12-31', DATE '2026-12-31', DATE '2026-07-31'])[period_no];
            first_shipment := (ARRAY[DATE '2025-02-10', DATE '2026-02-10', DATE '2025-08-10'])[period_no];
            key := tag || ':' || company_no || ':' || period_no;
            contract_id := md5(key || ':contract')::uuid;
            total := 120000000 + company_no * 15000000 + period_no * 10000000;
            INSERT INTO contract (id, version, created_by, created_date, customer_id,
                internal_id, internal_date, internal_date_end, payment_type, sum_total, days_payment)
            VALUES (contract_id, 1, tag, now(), customer_id,
                'DEMO-OG-' || company_no || '-' || period_label, start_date, end_date,
                (ARRAY['A', 'B', 'C'])[period_no], total, 15)
            ON CONFLICT (id) DO NOTHING;

            FOR project_no IN 1..2 LOOP
                project_id := md5(key || ':project:' || project_no)::uuid;
                sector_no := ((company_no + project_no - 2) % 4) + 1;
                sector_id := md5(tag || ':sector:' || sector_no)::uuid;
                project_total := total * CASE WHEN project_no = 1 THEN 0.60 ELSE 0.40 END;
                INSERT INTO project (id, version, created_by, created_date, name, line_of_business_id, contract_id)
                VALUES (project_id, 1, tag, now(),
                    works[sector_no] || ' — ' || companies[company_no] || ', ' || regions[company_no]
                    || ', ' || period_label || ' (демо)', sector_id, contract_id)
                ON CONFLICT (id) DO NOTHING;

                payment_id := md5(project_id::text || ':payment')::uuid;
                shipment_id := md5(project_id::text || ':shipment')::uuid;
                INSERT INTO payment_schedule (id, version, created_by, created_date, project_id)
                VALUES (payment_id, 1, tag, now(), project_id) ON CONFLICT (id) DO NOTHING;
                INSERT INTO shipment_schedule (id, version, created_by, created_date, project_id)
                VALUES (shipment_id, 1, tag, now(), project_id) ON CONFLICT (id) DO NOTHING;

                FOR item_no IN 1..6 LOOP
                    shipment_date := (first_shipment + make_interval(months => (item_no - 1) * 2))::date
                        + (project_no - 1) * 3;
                    installment := project_total * weights[item_no];
                    INSERT INTO shipment_schedule_item (id, version, created_by, created_date, schedule_id, item_date, amount)
                    VALUES (md5(shipment_id::text || ':' || item_no)::uuid, 1, tag, now(),
                        shipment_id, shipment_date, installment) ON CONFLICT (id) DO NOTHING;
                    INSERT INTO payment_schedule_item (id, version, created_by, created_date, schedule_id, item_date, amount)
                    VALUES (md5(payment_id::text || ':' || item_no)::uuid, 1, tag, now(),
                        payment_id, shipment_date + 15, installment) ON CONFLICT (id) DO NOTHING;
                END LOOP;
            END LOOP;
        END LOOP;
    END LOOP;
END $$;

-- Abort the entire seed if any financial/date invariant fails.
DO $$
DECLARE
    tag constant text := 'demo-oilgas-2025-2026';
BEGIN
    IF (SELECT count(*) FROM customer WHERE created_by = tag AND deleted_date IS NULL) <> 8
        OR (SELECT count(*) FROM contract WHERE created_by = tag AND deleted_date IS NULL) <> 24
        OR (SELECT count(*) FROM project WHERE created_by = tag AND deleted_date IS NULL) <> 48
        OR (SELECT count(*) FROM payment_schedule_item WHERE created_by = tag AND deleted_date IS NULL) <> 288
        OR (SELECT count(*) FROM shipment_schedule_item WHERE created_by = tag AND deleted_date IS NULL) <> 288 THEN
        RAISE EXCEPTION 'Unexpected demo record counts';
    END IF;
    IF EXISTS (
        SELECT 1 FROM contract c WHERE c.created_by = tag AND (
            c.sum_total <> (SELECT sum(i.amount) FROM project p JOIN payment_schedule s ON s.project_id=p.id
                JOIN payment_schedule_item i ON i.schedule_id=s.id WHERE p.contract_id=c.id AND i.deleted_date IS NULL)
            OR c.sum_total <> (SELECT sum(i.amount) FROM project p JOIN shipment_schedule s ON s.project_id=p.id
                JOIN shipment_schedule_item i ON i.schedule_id=s.id WHERE p.contract_id=c.id AND i.deleted_date IS NULL)
        )
    ) THEN RAISE EXCEPTION 'Contract and schedule amounts do not reconcile'; END IF;
    IF EXISTS (
        SELECT 1 FROM (
            SELECT s.project_id, i.item_date, i.amount FROM payment_schedule_item i JOIN payment_schedule s ON s.id=i.schedule_id WHERE i.created_by=tag
            UNION ALL
            SELECT s.project_id, i.item_date, i.amount FROM shipment_schedule_item i JOIN shipment_schedule s ON s.id=i.schedule_id WHERE i.created_by=tag
        ) i JOIN project p ON p.id=i.project_id JOIN contract c ON c.id=p.contract_id
        WHERE i.amount <= 0 OR i.item_date NOT BETWEEN c.internal_date AND c.internal_date_end
            OR i.item_date NOT BETWEEN DATE '2025-01-01' AND DATE '2026-12-31'
    ) THEN RAISE EXCEPTION 'Invalid schedule amount or date'; END IF;
END $$;

COMMIT;
